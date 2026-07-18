package com.paymentflow.analytics.service;

import com.paymentflow.analytics.domain.MerchantPaymentStats;
import com.paymentflow.analytics.event.AnalyticsEventPayload;
import com.paymentflow.analytics.repository.MerchantPaymentStatsRepository;
import com.paymentflow.analytics.repository.ProcessedEventRepository;
import com.paymentflow.common.dto.event.EventEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private MerchantPaymentStatsRepository merchantPaymentStatsRepository;
    @Mock
    private TransactionTemplate transactionTemplate;

    private AnalyticsService analyticsService;

    private final UUID merchantId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        analyticsService = new AnalyticsService(processedEventRepository, merchantPaymentStatsRepository, transactionTemplate);

        lenient().doAnswer(inv -> {
            Consumer<TransactionStatus> action = inv.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        lenient().when(merchantPaymentStatsRepository.findByMerchantIdAndCurrency(any(), any()))
                .thenReturn(Optional.empty());
        lenient().when(merchantPaymentStatsRepository.save(any(MerchantPaymentStats.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private EventEnvelope<AnalyticsEventPayload> envelope(String status, String previousStatus, long eventAmount) {
        AnalyticsEventPayload payload = new AnalyticsEventPayload(
                paymentId, merchantId, 10_000, "USD", status, previousStatus, eventAmount);
        return EventEnvelope.of("Payment" + status, paymentId.toString(), "corr-1", payload);
    }

    @Test
    void alreadyProcessedEventIsSkipped() {
        when(processedEventRepository.existsByEventId(any())).thenReturn(true);

        analyticsService.processEvent(envelope("AUTHORIZED", "CREATED", 10_000));

        verify(merchantPaymentStatsRepository, never()).save(any());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void createdIncrementsCreatedCount() {
        analyticsService.processEvent(envelope("CREATED", null, 10_000));

        ArgumentCaptor<MerchantPaymentStats> captor = ArgumentCaptor.forClass(MerchantPaymentStats.class);
        verify(merchantPaymentStatsRepository).save(captor.capture());
        assertThat(captor.getValue().getCreatedCount()).isEqualTo(1);
        verify(processedEventRepository).save(any());
    }

    @Test
    void authorizedIncrementsAuthorizedCount() {
        analyticsService.processEvent(envelope("AUTHORIZED", "CREATED", 10_000));

        ArgumentCaptor<MerchantPaymentStats> captor = ArgumentCaptor.forClass(MerchantPaymentStats.class);
        verify(merchantPaymentStatsRepository).save(captor.capture());
        assertThat(captor.getValue().getAuthorizedCount()).isEqualTo(1);
    }

    @Test
    void capturedIncrementsCountAndAccumulatesAmount() {
        analyticsService.processEvent(envelope("CAPTURED", "AUTHORIZED", 10_000));

        ArgumentCaptor<MerchantPaymentStats> captor = ArgumentCaptor.forClass(MerchantPaymentStats.class);
        verify(merchantPaymentStatsRepository).save(captor.capture());
        assertThat(captor.getValue().getCapturedCount()).isEqualTo(1);
        assertThat(captor.getValue().getTotalCapturedAmountMinor()).isEqualTo(10_000);
    }

    @Test
    void partiallyRefundedAccumulatesTheEventAmountNotTheTotal() {
        analyticsService.processEvent(envelope("PARTIALLY_REFUNDED", "CAPTURED", 4_000));

        ArgumentCaptor<MerchantPaymentStats> captor = ArgumentCaptor.forClass(MerchantPaymentStats.class);
        verify(merchantPaymentStatsRepository).save(captor.capture());
        assertThat(captor.getValue().getTotalRefundedAmountMinor()).isEqualTo(4_000);
    }

    @Test
    void voidedIncrementsVoidedCount() {
        analyticsService.processEvent(envelope("VOIDED", "AUTHORIZED", 10_000));

        ArgumentCaptor<MerchantPaymentStats> captor = ArgumentCaptor.forClass(MerchantPaymentStats.class);
        verify(merchantPaymentStatsRepository).save(captor.capture());
        assertThat(captor.getValue().getVoidedCount()).isEqualTo(1);
    }

    @Test
    void anExistingStatsRowIsReusedNotDuplicated() {
        MerchantPaymentStats existing = MerchantPaymentStats.open(merchantId, "USD");
        existing.incrementCreated();
        when(merchantPaymentStatsRepository.findByMerchantIdAndCurrency(merchantId, "USD"))
                .thenReturn(Optional.of(existing));

        analyticsService.processEvent(envelope("AUTHORIZED", "CREATED", 10_000));

        ArgumentCaptor<MerchantPaymentStats> captor = ArgumentCaptor.forClass(MerchantPaymentStats.class);
        verify(merchantPaymentStatsRepository).save(captor.capture());
        assertThat(captor.getValue().getCreatedCount()).isEqualTo(1);
        assertThat(captor.getValue().getAuthorizedCount()).isEqualTo(1);
    }

    @Test
    void retriesTheWholeTransactionOnOptimisticLockConflictThenSucceeds() {
        int[] callCount = {0};
        doAnswer(inv -> {
            callCount[0]++;
            if (callCount[0] < 3) {
                throw new ObjectOptimisticLockingFailureException(MerchantPaymentStats.class, "id");
            }
            Consumer<TransactionStatus> action = inv.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        analyticsService.processEvent(envelope("AUTHORIZED", "CREATED", 10_000));

        assertThat(callCount[0]).isEqualTo(3);
        verify(processedEventRepository).save(any());
    }

    @Test
    void givesUpAfterExhaustingRetries() {
        doAnswer(inv -> {
            throw new ObjectOptimisticLockingFailureException(MerchantPaymentStats.class, "id");
        }).when(transactionTemplate).executeWithoutResult(any());

        assertThatThrownBy(() -> analyticsService.processEvent(envelope("AUTHORIZED", "CREATED", 10_000)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}
