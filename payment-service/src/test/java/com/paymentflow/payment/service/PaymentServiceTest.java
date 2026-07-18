package com.paymentflow.payment.service;

import com.paymentflow.common.dto.page.PageResponse;
import com.paymentflow.common.exception.BadRequestException;
import com.paymentflow.common.exception.ResourceNotFoundException;
import com.paymentflow.payment.domain.Payment;
import com.paymentflow.payment.domain.PaymentStatus;
import com.paymentflow.payment.dto.CreatePaymentRequest;
import com.paymentflow.payment.dto.PaymentResponse;
import com.paymentflow.payment.dto.RefundRequest;
import com.paymentflow.payment.event.PaymentEventPublisher;
import com.paymentflow.payment.idempotency.IdempotencyService;
import com.paymentflow.payment.mapper.PaymentMapper;
import com.paymentflow.payment.merchant.MerchantResolver;
import com.paymentflow.payment.repository.PaymentRepository;
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

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Spy
    private PaymentMapper paymentMapper = new PaymentMapper();
    @Mock
    private PaymentEventPublisher eventPublisher;
    @Mock
    private IdempotencyService idempotencyService;
    @Mock
    private MerchantResolver merchantResolver;
    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private PaymentService paymentService;

    private final UUID merchantId = UUID.randomUUID();

    @BeforeEach
    void passThroughTransactionAndIdempotencyWrappers() {
        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });
        lenient().when(idempotencyService.guarded(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            Supplier<?> supplier = inv.getArgument(4);
            return supplier.get();
        });
        lenient().when(merchantResolver.resolveCallerMerchantId()).thenReturn(merchantId);
        lenient().when(idempotencyService.fingerprint(any(), any())).thenReturn("fingerprint");
    }

    @Test
    void createRequiresAnIdempotencyKey() {
        CreatePaymentRequest request = new CreatePaymentRequest(1000, "USD", "x");

        assertThatThrownBy(() -> paymentService.create(request, null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> paymentService.create(request, "  ")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void createSavesAPendingPaymentAndPublishesAnEvent() {
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentResponse response = paymentService.create(new CreatePaymentRequest(5000, "USD", "desc"), "key-1");

        assertThat(response.amountMinor()).isEqualTo(5000);
        assertThat(response.status()).isEqualTo("CREATED");
        verify(eventPublisher).publish(any(Payment.class), eq("PaymentCreated"), isNull());
        verify(idempotencyService).record(eq(merchantId), eq("key-1"), any(), eq(201), any());
    }

    @Test
    void authorizeTransitionsTheCallersOwnedPayment() {
        Payment payment = Payment.create(merchantId, 5000, "USD", null);
        when(paymentRepository.findByIdAndMerchantId(any(), eq(merchantId))).thenReturn(Optional.of(payment));

        PaymentResponse response = paymentService.authorize(UUID.randomUUID(), "key-2");

        assertThat(response.status()).isEqualTo("AUTHORIZED");
        verify(eventPublisher).publish(payment, "PaymentAuthorized", PaymentStatus.CREATED);
    }

    @Test
    void operatingOnAnUnownedOrMissingPaymentIsNotFound() {
        when(paymentRepository.findByIdAndMerchantId(any(), eq(merchantId))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.authorize(UUID.randomUUID(), "key-3"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void captureThenRefundWithNoAmountRefundsTheFullCapturedAmount() {
        Payment payment = Payment.create(merchantId, 5000, "USD", null);
        payment.authorize();
        payment.capture();
        when(paymentRepository.findByIdAndMerchantId(any(), eq(merchantId))).thenReturn(Optional.of(payment));

        PaymentResponse response = paymentService.refund(UUID.randomUUID(), new RefundRequest(null), "key-4");

        assertThat(response.status()).isEqualTo("REFUNDED");
        assertThat(response.refundedAmountMinor()).isEqualTo(5000);
        verify(eventPublisher).publish(payment, "PaymentRefunded", PaymentStatus.CAPTURED);
    }

    @Test
    void partialRefundPublishesThePartiallyRefundedEventType() {
        Payment payment = Payment.create(merchantId, 5000, "USD", null);
        payment.authorize();
        payment.capture();
        when(paymentRepository.findByIdAndMerchantId(any(), eq(merchantId))).thenReturn(Optional.of(payment));

        PaymentResponse response = paymentService.refund(UUID.randomUUID(), new RefundRequest(2000L), "key-5");

        assertThat(response.status()).isEqualTo("PARTIALLY_REFUNDED");
        verify(eventPublisher).publish(payment, "PaymentPartiallyRefunded", PaymentStatus.CAPTURED);
    }

    @Test
    void listDelegatesToTheRepositoryScopedToTheCallersMerchant() {
        when(paymentRepository.findByMerchantId(eq(merchantId), any())).thenReturn(Page.empty());

        PageResponse<PaymentResponse> page = paymentService.list(PageRequest.of(0, 20));

        assertThat(page.content()).isEmpty();
    }
}
