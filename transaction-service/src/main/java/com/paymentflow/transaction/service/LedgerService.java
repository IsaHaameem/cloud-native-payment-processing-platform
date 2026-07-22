package com.paymentflow.transaction.service;

import com.paymentflow.common.dto.event.EventEnvelope;
import com.paymentflow.transaction.domain.Account;
import com.paymentflow.transaction.domain.AccountType;
import com.paymentflow.transaction.domain.Direction;
import com.paymentflow.transaction.domain.LedgerEntry;
import com.paymentflow.transaction.domain.LedgerTransaction;
import com.paymentflow.transaction.domain.ProcessedEvent;
import com.paymentflow.transaction.event.PaymentLedgerEventPayload;
import com.paymentflow.transaction.repository.AccountRepository;
import com.paymentflow.transaction.repository.LedgerEntryRepository;
import com.paymentflow.transaction.repository.LedgerTransactionRepository;
import com.paymentflow.transaction.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Posts double-entry ledger transactions for payment lifecycle events, idempotently
 * (D2 — a redelivered event is a durable no-op via {@code processed_events}).
 *
 * <p>Postings, per account normalcy (D-decision, confirmed with the user):
 * <ul>
 *   <li>{@code AUTHORIZED} — Debit {@code PLATFORM_CLEARING}, Credit
 *       {@code MERCHANT_PENDING}: a pending obligation is recognized.</li>
 *   <li>{@code CAPTURED} — Debit {@code MERCHANT_PENDING}, Credit
 *       {@code MERCHANT_SETTLED}: the obligation settles into a real payable.</li>
 *   <li>{@code REFUNDED} / {@code PARTIALLY_REFUNDED} — Debit
 *       {@code MERCHANT_SETTLED}, Credit {@code PLATFORM_CLEARING}: funds flow back.</li>
 *   <li>{@code VOIDED} / {@code FAILED} — only reversed (Debit {@code MERCHANT_PENDING},
 *       Credit {@code PLATFORM_CLEARING}) when {@code previousStatus} was
 *       {@code AUTHORIZED}; voiding/failing straight from {@code CREATED} never posted
 *       anything, so there is nothing to reverse.</li>
 *   <li>{@code CREATED} — no ledger impact (no money has moved or been promised yet).</li>
 * </ul>
 *
 * <p>The whole event-processing transaction is retried from scratch (not just the
 * account update) on either an optimistic-lock conflict on a shared account (e.g.
 * every event touches {@code PLATFORM_CLEARING}) or a race creating a brand-new
 * account — Postgres aborts the rest of a transaction after a constraint violation,
 * so a nested catch-and-continue for the latter isn't viable without a savepoint;
 * retrying the whole (short, idempotent-on-retry) transaction is simpler and correct.
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);
    // Generous on purpose: every event touching the same currency shares one
    // PLATFORM_CLEARING account, so under real concurrent load (multiple partitions,
    // listener concurrency > 1) many events can race for the same row. A small
    // jittered backoff between attempts spreads out retries instead of them
    // immediately colliding again.
    private static final int MAX_ATTEMPTS = 10;
    private static final long BACKOFF_BASE_MILLIS = 20;
    // A null envelope mode (an in-flight pre-M16 message, or any not-yet-migrated producer)
    // is read as live — the same backfill semantics every merchant-scoped table applies to
    // its pre-M16 rows (M16.1's EventEnvelope contract).
    private static final String DEFAULT_MODE = "live";

    private final ProcessedEventRepository processedEventRepository;
    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository ledgerTransactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;

    public LedgerService(ProcessedEventRepository processedEventRepository, AccountRepository accountRepository,
                         LedgerTransactionRepository ledgerTransactionRepository,
                         LedgerEntryRepository ledgerEntryRepository, TransactionTemplate transactionTemplate,
                         MeterRegistry meterRegistry) {
        this.processedEventRepository = processedEventRepository;
        this.accountRepository = accountRepository;
        this.ledgerTransactionRepository = ledgerTransactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.transactionTemplate = transactionTemplate;
        this.meterRegistry = meterRegistry;
    }

    public void processEvent(EventEnvelope<PaymentLedgerEventPayload> envelope) {
        for (int attempt = 1; ; attempt++) {
            try {
                transactionTemplate.executeWithoutResult(status -> processInTransaction(envelope));
                return;
            } catch (OptimisticLockingFailureException | DataIntegrityViolationException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                log.warn("Retrying event {} (attempt {}/{}) after {}", envelope.eventId(), attempt,
                        MAX_ATTEMPTS, e.getClass().getSimpleName());
                meterRegistry.counter("ledger_posting_retries_total").increment();
                backoff(attempt);
            }
        }
    }

    private static void backoff(int attempt) {
        try {
            long jitterMillis = BACKOFF_BASE_MILLIS * attempt + (long) (Math.random() * BACKOFF_BASE_MILLIS);
            Thread.sleep(jitterMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void processInTransaction(EventEnvelope<PaymentLedgerEventPayload> envelope) {
        if (processedEventRepository.existsByEventId(envelope.eventId())) {
            log.debug("Event {} already processed, skipping", envelope.eventId());
            return;
        }

        PaymentLedgerEventPayload payload = envelope.payload();
        String mode = envelope.mode() == null ? DEFAULT_MODE : envelope.mode();
        switch (payload.status()) {
            case "AUTHORIZED" -> postAuthorized(envelope, payload, mode);
            case "CAPTURED" -> postCaptured(envelope, payload, mode);
            case "REFUNDED", "PARTIALLY_REFUNDED" -> postRefund(envelope, payload, mode);
            case "VOIDED", "FAILED" -> postReversalIfPreviouslyAuthorized(envelope, payload, mode);
            default -> log.debug("No ledger impact for status {} (event {})", payload.status(), envelope.eventId());
        }

        processedEventRepository.save(ProcessedEvent.of(envelope.eventId(), envelope.eventType()));
    }

    private void postAuthorized(EventEnvelope<PaymentLedgerEventPayload> envelope, PaymentLedgerEventPayload payload,
                                String mode) {
        Account clearing = getOrCreateAccount(AccountType.PLATFORM_CLEARING, null, payload.currency(), mode);
        Account pending = getOrCreateAccount(AccountType.MERCHANT_PENDING, payload.merchantId(), payload.currency(), mode);
        post(envelope, payload, mode, "Payment authorized — pending obligation recognized", clearing, pending);
    }

    private void postCaptured(EventEnvelope<PaymentLedgerEventPayload> envelope, PaymentLedgerEventPayload payload,
                              String mode) {
        Account pending = getOrCreateAccount(AccountType.MERCHANT_PENDING, payload.merchantId(), payload.currency(), mode);
        Account settled = getOrCreateAccount(AccountType.MERCHANT_SETTLED, payload.merchantId(), payload.currency(), mode);
        post(envelope, payload, mode, "Payment captured — pending obligation settled", pending, settled);
    }

    private void postRefund(EventEnvelope<PaymentLedgerEventPayload> envelope, PaymentLedgerEventPayload payload,
                            String mode) {
        Account settled = getOrCreateAccount(AccountType.MERCHANT_SETTLED, payload.merchantId(), payload.currency(), mode);
        Account clearing = getOrCreateAccount(AccountType.PLATFORM_CLEARING, null, payload.currency(), mode);
        post(envelope, payload, mode, "Payment refund", settled, clearing);
    }

    private void postReversalIfPreviouslyAuthorized(EventEnvelope<PaymentLedgerEventPayload> envelope,
                                                     PaymentLedgerEventPayload payload, String mode) {
        if (!"AUTHORIZED".equals(payload.previousStatus())) {
            log.debug("Payment {} moved to {} from {} — nothing was posted yet, nothing to reverse",
                    payload.paymentId(), payload.status(), payload.previousStatus());
            return;
        }
        Account pending = getOrCreateAccount(AccountType.MERCHANT_PENDING, payload.merchantId(), payload.currency(), mode);
        Account clearing = getOrCreateAccount(AccountType.PLATFORM_CLEARING, null, payload.currency(), mode);
        post(envelope, payload, mode, "Payment " + payload.status().toLowerCase() + " after authorization — reversal",
                pending, clearing);
    }

    /** Writes one balanced journal entry (debit == credit) and updates both accounts' running balances. */
    private void post(EventEnvelope<PaymentLedgerEventPayload> envelope, PaymentLedgerEventPayload payload,
                      String mode, String description, Account debitAccount, Account creditAccount) {
        long amountMinor = payload.eventAmountMinor();

        // Accounts must be saved (assigning an id to a brand-new one — GenerationType.UUID
        // is client-side, so save() populates getId() immediately) *before* the ledger
        // entries below are built, or a first-ever posting to a new account tries to
        // insert a LedgerEntry with a null account_id.
        debitAccount.apply(Direction.DEBIT, amountMinor);
        creditAccount.apply(Direction.CREDIT, amountMinor);
        accountRepository.save(debitAccount);
        accountRepository.save(creditAccount);

        LedgerTransaction transaction = ledgerTransactionRepository.save(
                LedgerTransaction.of(payload.paymentId(), envelope.eventId(), envelope.eventType(), mode, description));
        ledgerEntryRepository.save(LedgerEntry.of(
                transaction.getId(), debitAccount.getId(), Direction.DEBIT, amountMinor, payload.currency(), mode));
        ledgerEntryRepository.save(LedgerEntry.of(
                transaction.getId(), creditAccount.getId(), Direction.CREDIT, amountMinor, payload.currency(), mode));

        // Business metric (M13): one balanced posting per lifecycle event, by event type
        // and currency — the ledger-activity counterpart to payment-service's
        // payment_lifecycle_events_total, recorded at the one place every posting path
        // (authorize/capture/refund/reversal) already funnels through.
        meterRegistry.counter("ledger_postings_total", "eventType", envelope.eventType(), "currency", payload.currency())
                .increment();
    }

    /**
     * Looks up the account, or builds a new (not-yet-persisted) one — {@code post(...)}'s
     * later {@code accountRepository.save(...)} call, after the balance is applied,
     * handles the actual insert either way. Saving here too would just be a redundant
     * extra round trip for a brand-new account.
     */
    private Account getOrCreateAccount(AccountType type, UUID ownerId, String currency, String mode) {
        return accountRepository.findByAccountTypeAndOwnerIdAndCurrencyAndMode(type, ownerId, currency, mode)
                .orElseGet(() -> Account.open(type, ownerId, currency, mode));
    }
}
