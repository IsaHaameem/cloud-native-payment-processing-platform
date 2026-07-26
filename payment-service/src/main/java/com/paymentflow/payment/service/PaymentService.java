package com.paymentflow.payment.service;

import com.paymentflow.common.dto.event.EventEnvelope;
import com.paymentflow.common.dto.page.PageResponse;
import com.paymentflow.common.exception.BadRequestException;
import com.paymentflow.common.exception.ResourceNotFoundException;
import com.paymentflow.payment.authorization.AuthorizationAdvisor;
import com.paymentflow.payment.authorization.AuthorizationDecision;
import com.paymentflow.payment.authorization.AuthorizationRequest;
import com.paymentflow.payment.domain.OutboxEvent;
import com.paymentflow.payment.domain.Payment;
import com.paymentflow.payment.domain.PaymentStatus;
import com.paymentflow.payment.domain.Refund;
import com.paymentflow.payment.dto.CreatePaymentRequest;
import com.paymentflow.payment.dto.PaymentResponse;
import com.paymentflow.payment.dto.RefundRequest;
import com.paymentflow.payment.event.PaymentEventPayload;
import com.paymentflow.payment.event.PaymentEventPublisher;
import com.paymentflow.payment.event.ProcessedEvent;
import com.paymentflow.payment.exception.IllegalPaymentStateTransitionException;
import com.paymentflow.payment.idempotency.IdempotencyService;
import com.paymentflow.payment.mapper.PaymentMapper;
import com.paymentflow.payment.merchant.MerchantResolver;
import com.paymentflow.payment.merchant.MerchantSummary;
import com.paymentflow.payment.mode.RequestModeResolver;
import com.paymentflow.payment.repository.OutboxEventRepository;
import com.paymentflow.payment.repository.PaymentRepository;
import com.paymentflow.payment.repository.ProcessedEventRepository;
import com.paymentflow.payment.repository.RefundRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Payment lifecycle orchestration: resolves the caller's merchant (Feign), guards
 * every mutation with the idempotency service, and runs the actual state change +
 * outbox write inside a {@link TransactionTemplate} block (see
 * {@link IdempotencyService} for why not plain {@code @Transactional} here).
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final int MAX_DEFERRED_CAPTURE_ATTEMPTS = 5;
    private static final long DEFERRED_CAPTURE_BACKOFF_BASE_MILLIS = 20;

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final PaymentMapper paymentMapper;
    private final PaymentEventPublisher eventPublisher;
    private final IdempotencyService idempotencyService;
    private final MerchantResolver merchantResolver;
    private final RequestModeResolver requestModeResolver;
    private final TransactionTemplate transactionTemplate;
    private final AuthorizationAdvisor authorizationAdvisor;
    private final OutboxEventRepository outboxEventRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    public PaymentService(PaymentRepository paymentRepository, RefundRepository refundRepository,
                          PaymentMapper paymentMapper,
                          PaymentEventPublisher eventPublisher, IdempotencyService idempotencyService,
                          MerchantResolver merchantResolver, RequestModeResolver requestModeResolver,
                          TransactionTemplate transactionTemplate, AuthorizationAdvisor authorizationAdvisor,
                          OutboxEventRepository outboxEventRepository, ProcessedEventRepository processedEventRepository,
                          ObjectMapper objectMapper) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.paymentMapper = paymentMapper;
        this.eventPublisher = eventPublisher;
        this.idempotencyService = idempotencyService;
        this.merchantResolver = merchantResolver;
        this.requestModeResolver = requestModeResolver;
        this.transactionTemplate = transactionTemplate;
        this.authorizationAdvisor = authorizationAdvisor;
        this.outboxEventRepository = outboxEventRepository;
        this.processedEventRepository = processedEventRepository;
        this.objectMapper = objectMapper;
    }

    public PaymentResponse create(CreatePaymentRequest request, String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        String mode = requestModeResolver.resolve();
        MerchantSummary merchant = merchantResolver.resolveCallerMerchant();
        UUID merchantId = merchant.id();
        String fingerprint = idempotencyService.fingerprint("POST:/api/v1/payments", request);

        return idempotencyService.guarded(merchantId, mode, idempotencyKey, fingerprint, PaymentResponse.class, () ->
                transactionTemplate.execute(status -> {
                    Payment payment = paymentRepository.save(Payment.create(
                            merchantId, mode, request.amountMinor(), request.currency(), request.description(),
                            request.paymentMethodToken(), paymentMapper.writeMetadata(request.metadata())));
                    eventPublisher.publish(payment, "PaymentCreated", null, payment.getAmountMinor(), merchant);
                    PaymentResponse response = paymentMapper.toResponse(payment);
                    idempotencyService.record(merchantId, mode, idempotencyKey, fingerprint, 201, response);
                    return response;
                }));
    }

    /**
     * Unlike capture/void/refund below, authorize cannot use {@link #mutate}: the
     * sandbox advisory call (M17.4, D129) must happen <em>before</em> any transaction
     * opens — holding a pooled DB connection across a call that can legitimately run
     * for seconds (a slow test card) risks exhausting the pool under load. The read
     * here is advisory only: a fail-fast FSM check plus the immutable facts (token,
     * amount, currency) the decision needs. Authority over the payment's actual state
     * is re-established below, under optimistic locking, once the decision returns —
     * never assumed from this earlier read.
     */
    public PaymentResponse authorize(UUID paymentId, String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        String mode = requestModeResolver.resolve();
        MerchantSummary merchant = merchantResolver.resolveCallerMerchant();
        UUID merchantId = merchant.id();
        String fingerprint = idempotencyService.fingerprint("POST:/api/v1/payments/" + paymentId + "/authorize", null);

        return idempotencyService.guarded(merchantId, mode, idempotencyKey, fingerprint, PaymentResponse.class, () -> {
            Payment preview = getOwnedPayment(paymentId, merchantId, mode);
            if (!preview.getStatus().canTransitionTo(PaymentStatus.AUTHORIZED)) {
                throw new IllegalPaymentStateTransitionException(preview.getStatus(), PaymentStatus.AUTHORIZED);
            }
            AuthorizationDecision decision = authorizationAdvisor.advise(new AuthorizationRequest(
                    preview.getId(), merchantId, mode, preview.getPaymentMethodToken(), preview.getAmountMinor(),
                    preview.getCurrency()));

            return transactionTemplate.execute(status -> {
                Payment payment = getOwnedPayment(paymentId, merchantId, mode);
                PaymentStatus previous = payment.getStatus();
                MutationOutcome outcome = applyAuthorizationDecision(payment, decision);
                eventPublisher.publish(payment, outcome.eventType(), previous, outcome.eventAmountMinor(), merchant);
                PaymentResponse response = paymentMapper.toResponse(payment);
                idempotencyService.record(merchantId, mode, idempotencyKey, fingerprint, 200, response);
                return response;
            });
        });
    }

    /**
     * Translates the provider-neutral {@link AuthorizationDecision} into the payment's
     * own state transition and lifecycle event (M17.4). A DECLINED/ERROR decision fails
     * the payment outright — there is no intermediate FSM state for an authorization
     * that requires further customer action; {@code SandboxAuthorizationAdvisor} already
     * folds that case into DECLINED before it ever reaches here.
     */
    private static MutationOutcome applyAuthorizationDecision(Payment payment, AuthorizationDecision decision) {
        return switch (decision.outcome()) {
            case APPROVED -> {
                payment.authorize();
                yield new MutationOutcome("PaymentAuthorized", payment.getAmountMinor());
            }
            case DECLINED -> {
                payment.fail(decision.declineCode());
                yield new MutationOutcome("PaymentFailed", payment.getAmountMinor());
            }
            case ERROR -> {
                payment.fail(decision.errorCode());
                yield new MutationOutcome("PaymentFailed", payment.getAmountMinor());
            }
            case PENDING -> throw new IllegalStateException(
                    "PENDING authorization outcomes are not produced by any adapter until M17.6.");
        };
    }

    /**
     * Applies a capture that was deferred at authorize time (M17.6) — the SAME FSM
     * guard {@link Payment#capture()} a synchronous call uses, triggered by an inbound
     * Kafka event instead of a client request. No {@code Idempotency-Key}/HTTP replay
     * involved here at all: the caller's own dedup (D2, {@code ProcessedEvent}, keyed
     * on {@code eventId}) is what makes redelivery safe, wrapped around the same
     * transactional attempt so a redelivered event can never be recorded processed
     * without the capture actually having been applied (or vice versa).
     *
     * <p>An already-CAPTURED (or otherwise no-longer-{@code AUTHORIZED}) payment is not
     * an error here — the client may have captured it explicitly before the deferred
     * event fired, or a redelivery may arrive after the first delivery already applied
     * it. Either way this is a durable no-op, not a failure to retry.
     *
     * <p>Retried on {@link OptimisticLockingFailureException} (a genuine race against a
     * concurrent client-initiated mutation of the same payment) — mirrors
     * transaction-service's {@code LedgerService.processEvent} (M6) exactly: the whole
     * attempt (dedup check included) is retried from scratch under a fresh transaction,
     * not just the FSM mutation, since Postgres aborts the rest of a transaction after
     * any conflict.
     */
    public void applyDeferredCapture(UUID eventId, String eventType, UUID paymentId, String mode) {
        for (int attempt = 1; ; attempt++) {
            try {
                transactionTemplate.executeWithoutResult(
                        status -> applyDeferredCaptureInTransaction(eventId, eventType, paymentId, mode));
                return;
            } catch (OptimisticLockingFailureException e) {
                if (attempt >= MAX_DEFERRED_CAPTURE_ATTEMPTS) {
                    throw e;
                }
                log.warn("Retrying deferred capture for payment {} (attempt {}/{}) after {}", paymentId, attempt,
                        MAX_DEFERRED_CAPTURE_ATTEMPTS, e.getClass().getSimpleName());
                backoff(attempt);
            }
        }
    }

    private void applyDeferredCaptureInTransaction(UUID eventId, String eventType, UUID paymentId, String mode) {
        if (processedEventRepository.existsByEventId(eventId)) {
            log.debug("Event {} already processed, skipping", eventId);
            return;
        }

        Optional<Payment> maybePayment = paymentRepository.findById(paymentId);
        if (maybePayment.isPresent() && maybePayment.get().getMode().equals(mode)) {
            applyCaptureIfStillPending(maybePayment.get());
        } else {
            log.warn("Deferred capture for payment {} (mode {}) skipped — payment not found in that mode", paymentId, mode);
        }

        processedEventRepository.save(ProcessedEvent.of(eventId, eventType));
    }

    private void applyCaptureIfStillPending(Payment payment) {
        PaymentStatus previous = payment.getStatus();
        try {
            payment.capture();
        } catch (IllegalPaymentStateTransitionException alreadyHandled) {
            log.debug("Payment {} is already past AUTHORIZED ({}) — deferred capture is a no-op", payment.getId(), previous);
            return;
        }
        MerchantSummary merchant = resolveMerchantSummaryForSystemEvent(payment);
        eventPublisher.publish(payment, "PaymentCaptured", previous, payment.getAmountMinor(), merchant);
    }

    /**
     * There is no caller/JWT to resolve a merchant through for a Kafka-triggered
     * mutation (M17.6) — every event already published for this payment carries the
     * merchant's contact email/webhook URL (D43), so the most recent one is the source
     * of truth here instead of a fresh merchant-service call this code path has no
     * request context to make.
     */
    private MerchantSummary resolveMerchantSummaryForSystemEvent(Payment payment) {
        OutboxEvent latest = outboxEventRepository.findTopByAggregateIdOrderByCreatedAtDesc(payment.getId())
                .orElseThrow(() -> new IllegalStateException("No prior event exists for payment " + payment.getId()));
        EventEnvelope<PaymentEventPayload> envelope = objectMapper.readValue(latest.getPayload(), objectMapper
                .getTypeFactory().constructParametricType(EventEnvelope.class, PaymentEventPayload.class));
        PaymentEventPayload payload = envelope.payload();
        return new MerchantSummary(payment.getMerchantId(), payload.merchantContactEmail(), payload.merchantWebhookUrl());
    }

    private static void backoff(int attempt) {
        try {
            long jitterMillis = DEFERRED_CAPTURE_BACKOFF_BASE_MILLIS * attempt
                    + (long) (Math.random() * DEFERRED_CAPTURE_BACKOFF_BASE_MILLIS);
            Thread.sleep(jitterMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public PaymentResponse capture(UUID paymentId, String idempotencyKey) {
        return mutate(paymentId, idempotencyKey, "capture", null, payment -> {
            payment.capture();
            return new MutationOutcome("PaymentCaptured", payment.getAmountMinor());
        });
    }

    public PaymentResponse voidPayment(UUID paymentId, String idempotencyKey) {
        return mutate(paymentId, idempotencyKey, "void", null, payment -> {
            payment.voidPayment();
            return new MutationOutcome("PaymentVoided", payment.getAmountMinor());
        });
    }

    public PaymentResponse refund(UUID paymentId, RefundRequest request, String idempotencyKey) {
        return mutate(paymentId, idempotencyKey, "refund", request, payment -> {
            long remaining = payment.getCapturedAmountMinor() - payment.getRefundedAmountMinor();
            long amount = (request != null && request.amountMinor() != null) ? request.amountMinor() : remaining;
            // The FSM decides first — an illegal or over-large refund throws here, before
            // any refund row exists, so a rejected refund leaves no trace of having been
            // attempted (which is the same behaviour as before M19).
            payment.refund(amount);
            // M19.3: the refund becomes an object. Written in the same transaction as the
            // accumulator it increments, so the row and payments.refunded_amount_minor can
            // never disagree about what was refunded.
            refundRepository.save(Refund.succeeded(payment, amount,
                    request == null ? null : request.reason(),
                    paymentMapper.writeMetadata(request == null ? null : request.metadata())));
            String eventType = payment.getStatus() == PaymentStatus.REFUNDED ? "PaymentRefunded" : "PaymentPartiallyRefunded";
            return new MutationOutcome(eventType, amount);
        });
    }

    public PaymentResponse get(UUID paymentId) {
        String mode = requestModeResolver.resolve();
        UUID merchantId = merchantResolver.resolveCallerMerchant().id();
        return paymentMapper.toResponse(getOwnedPayment(paymentId, merchantId, mode));
    }

    public PageResponse<PaymentResponse> list(Pageable pageable) {
        String mode = requestModeResolver.resolve();
        UUID merchantId = merchantResolver.resolveCallerMerchant().id();
        Page<PaymentResponse> page = paymentRepository.findByMerchantIdAndMode(merchantId, mode, pageable)
                .map(paymentMapper::toResponse);
        return PageResponse.of(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements());
    }

    /**
     * Shared shape for authorize/capture/void/refund: resolve merchant, guard with
     * idempotency, load the caller's own payment inside the transaction, apply the
     * given state mutation, publish the resulting event, record the idempotent
     * response. {@code mutation} both mutates {@code payment} and reports back the
     * event type and the amount *this specific transition* moved — full amount for
     * authorize/capture/void, the incremental amount for a (possibly partial) refund.
     */
    private PaymentResponse mutate(UUID paymentId, String idempotencyKey, String operation, Object requestBody,
                                   Function<Payment, MutationOutcome> mutation) {
        requireIdempotencyKey(idempotencyKey);
        String mode = requestModeResolver.resolve();
        MerchantSummary merchant = merchantResolver.resolveCallerMerchant();
        UUID merchantId = merchant.id();
        String fingerprint = idempotencyService.fingerprint(
                "POST:/api/v1/payments/" + paymentId + "/" + operation, requestBody);

        return idempotencyService.guarded(merchantId, mode, idempotencyKey, fingerprint, PaymentResponse.class, () ->
                transactionTemplate.execute(status -> {
                    Payment payment = getOwnedPayment(paymentId, merchantId, mode);
                    PaymentStatus previous = payment.getStatus();
                    MutationOutcome outcome = mutation.apply(payment);
                    eventPublisher.publish(payment, outcome.eventType(), previous, outcome.eventAmountMinor(), merchant);
                    PaymentResponse response = paymentMapper.toResponse(payment);
                    idempotencyService.record(merchantId, mode, idempotencyKey, fingerprint, 200, response);
                    return response;
                }));
    }

    private record MutationOutcome(String eventType, long eventAmountMinor) {
    }

    // Scoped by mode as well as merchant: a payment in another mode is invisible here and
    // surfaces as 404 (§4.4 — a test credential must not even confirm a live payment exists).
    private Payment getOwnedPayment(UUID paymentId, UUID merchantId, String mode) {
        return paymentRepository.findByIdAndMerchantIdAndMode(paymentId, merchantId, mode)
                .orElseThrow(() -> ResourceNotFoundException.of("Payment", paymentId));
    }

    private static void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key header is required.");
        }
    }
}
