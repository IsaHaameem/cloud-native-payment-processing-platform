package com.paymentflow.payment.service;

import com.paymentflow.common.dto.page.PageResponse;
import com.paymentflow.common.exception.BadRequestException;
import com.paymentflow.common.exception.ResourceNotFoundException;
import com.paymentflow.payment.authorization.AuthorizationAdvisor;
import com.paymentflow.payment.authorization.AuthorizationDecision;
import com.paymentflow.payment.domain.Payment;
import com.paymentflow.payment.domain.PaymentStatus;
import com.paymentflow.payment.dto.CreatePaymentRequest;
import com.paymentflow.payment.dto.PaymentResponse;
import com.paymentflow.payment.dto.RefundRequest;
import com.paymentflow.payment.event.PaymentEventPublisher;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;
    // M19.3: refunds became objects, so the refund path now writes a row alongside the
    // accumulator. Mocked rather than asserted here — this suite is about the FSM and the
    // published event type; the row itself is covered by the integration tests.
    @Mock
    private RefundRepository refundRepository;
    @Spy
    private PaymentMapper paymentMapper = new PaymentMapper(tools.jackson.databind.json.JsonMapper.builder().build());
    @Mock
    private PaymentEventPublisher eventPublisher;
    @Mock
    private IdempotencyService idempotencyService;
    @Mock
    private MerchantResolver merchantResolver;
    @Mock
    private RequestModeResolver requestModeResolver;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private AuthorizationAdvisor authorizationAdvisor;
    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private PaymentService paymentService;

    private final UUID merchantId = UUID.randomUUID();
    private final MerchantSummary merchant = new MerchantSummary(merchantId, "billing@acme.test", "https://acme.test/hooks");

    @BeforeEach
    void passThroughTransactionAndIdempotencyWrappers() {
        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });
        lenient().doAnswer(inv -> {
            Consumer<org.springframework.transaction.TransactionStatus> action = inv.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        lenient().when(idempotencyService.guarded(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            Supplier<?> supplier = inv.getArgument(5);
            return supplier.get();
        });
        lenient().when(merchantResolver.resolveCallerMerchant()).thenReturn(merchant);
        lenient().when(requestModeResolver.resolve()).thenReturn("test");
        lenient().when(idempotencyService.fingerprint(any(), any())).thenReturn("fingerprint");
        lenient().when(authorizationAdvisor.advise(any())).thenReturn(AuthorizationDecision.approved());
    }

    @Test
    void createRequiresAnIdempotencyKey() {
        CreatePaymentRequest request = new CreatePaymentRequest(1000, "USD", "x", null, null);

        assertThatThrownBy(() -> paymentService.create(request, null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> paymentService.create(request, "  ")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void createSavesAPendingPaymentAndPublishesAnEvent() {
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentResponse response = paymentService.create(new CreatePaymentRequest(5000, "USD", "desc", null, null), "key-1");

        assertThat(response.amountMinor()).isEqualTo(5000);
        assertThat(response.status()).isEqualTo("CREATED");
        assertThat(response.mode()).isEqualTo("test");
        verify(eventPublisher).publish(any(Payment.class), eq("PaymentCreated"), isNull(), eq(5000L), eq(merchant));
        verify(idempotencyService).record(eq(merchantId), eq("test"), eq("key-1"), any(), eq(201), any());
    }

    @Test
    void authorizeTransitionsTheCallersOwnedPayment() {
        Payment payment = Payment.create(merchantId, "test", 5000, "USD", null, null, null);
        when(paymentRepository.findByIdAndMerchantIdAndMode(any(), eq(merchantId), eq("test")))
                .thenReturn(Optional.of(payment));

        PaymentResponse response = paymentService.authorize(UUID.randomUUID(), "key-2");

        assertThat(response.status()).isEqualTo("AUTHORIZED");
        verify(eventPublisher).publish(payment, "PaymentAuthorized", PaymentStatus.CREATED, 5000L, merchant);
    }

    @Test
    void authorizeFailsThePaymentOnADeclinedDecision() {
        Payment payment = Payment.create(merchantId, "test", 5000, "USD", "pm_card_chargeDeclined", null, null);
        when(paymentRepository.findByIdAndMerchantIdAndMode(any(), eq(merchantId), eq("test")))
                .thenReturn(Optional.of(payment));
        when(authorizationAdvisor.advise(any())).thenReturn(AuthorizationDecision.declined("card_declined"));

        PaymentResponse response = paymentService.authorize(UUID.randomUUID(), "key-declined");

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.failureReason()).isEqualTo("card_declined");
        verify(eventPublisher).publish(payment, "PaymentFailed", PaymentStatus.CREATED, 5000L, merchant);
    }

    @Test
    void authorizeFailsThePaymentOnAnErrorDecision() {
        Payment payment = Payment.create(merchantId, "test", 5000, "USD", "pm_card_processingError", null, null);
        when(paymentRepository.findByIdAndMerchantIdAndMode(any(), eq(merchantId), eq("test")))
                .thenReturn(Optional.of(payment));
        when(authorizationAdvisor.advise(any())).thenReturn(AuthorizationDecision.error("processing_error"));

        PaymentResponse response = paymentService.authorize(UUID.randomUUID(), "key-error");

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.failureReason()).isEqualTo("processing_error");
        verify(eventPublisher).publish(payment, "PaymentFailed", PaymentStatus.CREATED, 5000L, merchant);
    }

    @Test
    void authorizeNeverCallsTheAdvisorWhenThePaymentCannotBeAuthorized() {
        Payment payment = Payment.create(merchantId, "test", 5000, "USD", null, null, null);
        payment.authorize();
        when(paymentRepository.findByIdAndMerchantIdAndMode(any(), eq(merchantId), eq("test")))
                .thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> paymentService.authorize(UUID.randomUUID(), "key-illegal"))
                .isInstanceOf(IllegalPaymentStateTransitionException.class);

        verifyNoInteractions(authorizationAdvisor);
    }

    @Test
    void operatingOnAnUnownedOrMissingPaymentIsNotFound() {
        when(paymentRepository.findByIdAndMerchantIdAndMode(any(), eq(merchantId), eq("test")))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.authorize(UUID.randomUUID(), "key-3"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void captureThenRefundWithNoAmountRefundsTheFullCapturedAmount() {
        Payment payment = Payment.create(merchantId, "test", 5000, "USD", null, null, null);
        payment.authorize();
        payment.capture();
        when(paymentRepository.findByIdAndMerchantIdAndMode(any(), eq(merchantId), eq("test")))
                .thenReturn(Optional.of(payment));

        PaymentResponse response = paymentService.refund(UUID.randomUUID(), new RefundRequest(null, null, null), "key-4");

        assertThat(response.status()).isEqualTo("REFUNDED");
        assertThat(response.refundedAmountMinor()).isEqualTo(5000);
        verify(eventPublisher).publish(payment, "PaymentRefunded", PaymentStatus.CAPTURED, 5000L, merchant);
    }

    @Test
    void partialRefundPublishesThePartiallyRefundedEventType() {
        Payment payment = Payment.create(merchantId, "test", 5000, "USD", null, null, null);
        payment.authorize();
        payment.capture();
        when(paymentRepository.findByIdAndMerchantIdAndMode(any(), eq(merchantId), eq("test")))
                .thenReturn(Optional.of(payment));

        PaymentResponse response = paymentService.refund(UUID.randomUUID(), new RefundRequest(2000L, null, null), "key-5");

        assertThat(response.status()).isEqualTo("PARTIALLY_REFUNDED");
        verify(eventPublisher).publish(payment, "PaymentPartiallyRefunded", PaymentStatus.CAPTURED, 2000L, merchant);
    }

    @Test
    void listDelegatesToTheRepositoryScopedToTheCallersMerchantAndMode() {
        when(paymentRepository.findByMerchantIdAndMode(eq(merchantId), eq("test"), any())).thenReturn(Page.empty());

        PageResponse<PaymentResponse> page = paymentService.list(PageRequest.of(0, 20));

        assertThat(page.content()).isEmpty();
    }

    @Test
    void applyDeferredCaptureSkipsAnAlreadyProcessedEvent() {
        UUID eventId = UUID.randomUUID();
        when(processedEventRepository.existsByEventId(eventId)).thenReturn(true);

        paymentService.applyDeferredCapture(eventId, "DeferredOutcomeSettled", UUID.randomUUID(), "test");

        verify(paymentRepository, never()).findById(any());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void applyDeferredCaptureIsANoOpWhenThePaymentIsNotFound() {
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.empty());

        paymentService.applyDeferredCapture(UUID.randomUUID(), "DeferredOutcomeSettled", paymentId, "test");

        verifyNoInteractions(eventPublisher);
        verify(processedEventRepository).save(any());
    }

    @Test
    void applyDeferredCaptureIsANoOpForAModeMismatch() {
        UUID paymentId = UUID.randomUUID();
        Payment payment = Payment.create(merchantId, "live", 5000, "USD", null, null, null);
        payment.authorize();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

        paymentService.applyDeferredCapture(UUID.randomUUID(), "DeferredOutcomeSettled", paymentId, "test");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        verifyNoInteractions(eventPublisher);
        verify(processedEventRepository).save(any());
    }

    @Test
    void applyDeferredCaptureIsANoOpWhenThePaymentIsAlreadyCaptured() {
        UUID paymentId = UUID.randomUUID();
        Payment payment = Payment.create(merchantId, "test", 5000, "USD", null, null, null);
        payment.authorize();
        payment.capture();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

        paymentService.applyDeferredCapture(UUID.randomUUID(), "DeferredOutcomeSettled", paymentId, "test");

        verifyNoInteractions(eventPublisher);
        verify(processedEventRepository).save(any());
    }
}
