package com.paymentflow.transaction.service;

import com.paymentflow.common.dto.event.EventEnvelope;
import com.paymentflow.transaction.domain.Account;
import com.paymentflow.transaction.domain.AccountType;
import com.paymentflow.transaction.domain.Direction;
import com.paymentflow.transaction.domain.LedgerEntry;
import com.paymentflow.transaction.domain.LedgerTransaction;
import com.paymentflow.transaction.event.PaymentLedgerEventPayload;
import com.paymentflow.transaction.repository.AccountRepository;
import com.paymentflow.transaction.repository.LedgerEntryRepository;
import com.paymentflow.transaction.repository.LedgerTransactionRepository;
import com.paymentflow.transaction.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private LedgerTransactionRepository ledgerTransactionRepository;
    @Mock
    private LedgerEntryRepository ledgerEntryRepository;
    @Mock
    private TransactionTemplate transactionTemplate;

    private LedgerService ledgerService;

    private final UUID merchantId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ledgerService = new LedgerService(processedEventRepository, accountRepository,
                ledgerTransactionRepository, ledgerEntryRepository, transactionTemplate, new SimpleMeterRegistry());

        lenient().doAnswer(inv -> {
            Consumer<TransactionStatus> action = inv.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        lenient().when(accountRepository.findByAccountTypeAndOwnerIdAndCurrency(any(), any(), any()))
                .thenReturn(Optional.empty());
        lenient().when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(ledgerTransactionRepository.save(any(LedgerTransaction.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private EventEnvelope<PaymentLedgerEventPayload> envelope(String status, String previousStatus, long eventAmount) {
        PaymentLedgerEventPayload payload = new PaymentLedgerEventPayload(
                paymentId, merchantId, 10_000, "USD", status, previousStatus, eventAmount);
        return EventEnvelope.of("Payment" + status, paymentId.toString(), "corr-1", payload);
    }

    @Test
    void createdHasNoLedgerImpactButIsRecordedAsProcessed() {
        ledgerService.processEvent(envelope("CREATED", null, 10_000));

        verify(ledgerTransactionRepository, never()).save(any());
        verify(processedEventRepository).save(any());
    }

    @Test
    void alreadyProcessedEventIsSkipped() {
        when(processedEventRepository.existsByEventId(any())).thenReturn(true);

        ledgerService.processEvent(envelope("AUTHORIZED", "CREATED", 10_000));

        verify(ledgerTransactionRepository, never()).save(any());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void authorizedDebitsClearingAndCreditsMerchantPending() {
        ledgerService.processEvent(envelope("AUTHORIZED", "CREATED", 10_000));

        ArgumentCaptor<LedgerEntry> entryCaptor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository, times(2)).save(entryCaptor.capture());
        List<LedgerEntry> entries = entryCaptor.getAllValues();

        assertThat(entries).extracting(LedgerEntry::getDirection)
                .containsExactlyInAnyOrder(Direction.DEBIT, Direction.CREDIT);
        assertThat(entries).allMatch(e -> e.getAmountMinor() == 10_000);

        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository, times(2)).save(accountCaptor.capture());
        assertThat(accountCaptor.getAllValues())
                .extracting(Account::getAccountType)
                .containsExactlyInAnyOrder(AccountType.PLATFORM_CLEARING, AccountType.MERCHANT_PENDING);
    }

    @Test
    void capturedDebitsMerchantPendingAndCreditsMerchantSettled() {
        ledgerService.processEvent(envelope("CAPTURED", "AUTHORIZED", 10_000));

        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository, times(2)).save(accountCaptor.capture());
        assertThat(accountCaptor.getAllValues())
                .extracting(Account::getAccountType)
                .containsExactlyInAnyOrder(AccountType.MERCHANT_PENDING, AccountType.MERCHANT_SETTLED);
    }

    @Test
    void refundedDebitsMerchantSettledAndCreditsClearingWithTheEventAmountNotTheTotal() {
        ledgerService.processEvent(envelope("PARTIALLY_REFUNDED", "CAPTURED", 4_000));

        ArgumentCaptor<LedgerEntry> entryCaptor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository, times(2)).save(entryCaptor.capture());
        assertThat(entryCaptor.getAllValues()).allMatch(e -> e.getAmountMinor() == 4_000);

        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository, times(2)).save(accountCaptor.capture());
        assertThat(accountCaptor.getAllValues())
                .extracting(Account::getAccountType)
                .containsExactlyInAnyOrder(AccountType.MERCHANT_SETTLED, AccountType.PLATFORM_CLEARING);
    }

    @Test
    void voidedAfterAuthorizationReversesThePendingObligation() {
        ledgerService.processEvent(envelope("VOIDED", "AUTHORIZED", 10_000));

        verify(ledgerTransactionRepository).save(any());
        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository, times(2)).save(accountCaptor.capture());
        assertThat(accountCaptor.getAllValues())
                .extracting(Account::getAccountType)
                .containsExactlyInAnyOrder(AccountType.MERCHANT_PENDING, AccountType.PLATFORM_CLEARING);
    }

    @Test
    void voidedStraightFromCreatedHasNothingToReverse() {
        ledgerService.processEvent(envelope("VOIDED", "CREATED", 10_000));

        verify(ledgerTransactionRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
        verify(processedEventRepository).save(any());
    }

    @Test
    void failedAfterAuthorizationAlsoReverses() {
        ledgerService.processEvent(envelope("FAILED", "AUTHORIZED", 10_000));

        verify(ledgerTransactionRepository).save(any());
    }

    @Test
    void retriesTheWholeTransactionOnOptimisticLockConflictThenSucceeds() {
        int[] callCount = {0};
        doAnswer(inv -> {
            callCount[0]++;
            if (callCount[0] < 3) {
                throw new ObjectOptimisticLockingFailureException(Account.class, "id");
            }
            Consumer<TransactionStatus> action = inv.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        ledgerService.processEvent(envelope("AUTHORIZED", "CREATED", 10_000));

        assertThat(callCount[0]).isEqualTo(3);
        verify(processedEventRepository).save(any());
    }

    @Test
    void givesUpAfterExhaustingRetries() {
        doAnswer(inv -> {
            throw new ObjectOptimisticLockingFailureException(Account.class, "id");
        }).when(transactionTemplate).executeWithoutResult(any());

        assertThatThrownBy(() -> ledgerService.processEvent(envelope("AUTHORIZED", "CREATED", 10_000)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}
