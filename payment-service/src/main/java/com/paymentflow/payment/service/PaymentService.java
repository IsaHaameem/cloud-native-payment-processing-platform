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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

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

    private final PaymentRepository paymentRepository;
    private final PaymentMapper paymentMapper;
    private final PaymentEventPublisher eventPublisher;
    private final IdempotencyService idempotencyService;
    private final MerchantResolver merchantResolver;
    private final TransactionTemplate transactionTemplate;

    public PaymentService(PaymentRepository paymentRepository, PaymentMapper paymentMapper,
                          PaymentEventPublisher eventPublisher, IdempotencyService idempotencyService,
                          MerchantResolver merchantResolver, TransactionTemplate transactionTemplate) {
        this.paymentRepository = paymentRepository;
        this.paymentMapper = paymentMapper;
        this.eventPublisher = eventPublisher;
        this.idempotencyService = idempotencyService;
        this.merchantResolver = merchantResolver;
        this.transactionTemplate = transactionTemplate;
    }

    public PaymentResponse create(CreatePaymentRequest request, String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        UUID merchantId = merchantResolver.resolveCallerMerchantId();
        String fingerprint = idempotencyService.fingerprint("POST:/api/v1/payments", request);

        return idempotencyService.guarded(merchantId, idempotencyKey, fingerprint, PaymentResponse.class, () ->
                transactionTemplate.execute(status -> {
                    Payment payment = paymentRepository.save(
                            Payment.create(merchantId, request.amountMinor(), request.currency(), request.description()));
                    eventPublisher.publish(payment, "PaymentCreated", null);
                    PaymentResponse response = paymentMapper.toResponse(payment);
                    idempotencyService.record(merchantId, idempotencyKey, fingerprint, 201, response);
                    return response;
                }));
    }

    public PaymentResponse authorize(UUID paymentId, String idempotencyKey) {
        return mutate(paymentId, idempotencyKey, "authorize", null, payment -> {
            payment.authorize();
            return "PaymentAuthorized";
        });
    }

    public PaymentResponse capture(UUID paymentId, String idempotencyKey) {
        return mutate(paymentId, idempotencyKey, "capture", null, payment -> {
            payment.capture();
            return "PaymentCaptured";
        });
    }

    public PaymentResponse voidPayment(UUID paymentId, String idempotencyKey) {
        return mutate(paymentId, idempotencyKey, "void", null, payment -> {
            payment.voidPayment();
            return "PaymentVoided";
        });
    }

    public PaymentResponse refund(UUID paymentId, RefundRequest request, String idempotencyKey) {
        return mutate(paymentId, idempotencyKey, "refund", request, payment -> {
            long remaining = payment.getCapturedAmountMinor() - payment.getRefundedAmountMinor();
            long amount = (request != null && request.amountMinor() != null) ? request.amountMinor() : remaining;
            payment.refund(amount);
            return payment.getStatus() == PaymentStatus.REFUNDED ? "PaymentRefunded" : "PaymentPartiallyRefunded";
        });
    }

    public PaymentResponse get(UUID paymentId) {
        UUID merchantId = merchantResolver.resolveCallerMerchantId();
        return paymentMapper.toResponse(getOwnedPayment(paymentId, merchantId));
    }

    public PageResponse<PaymentResponse> list(Pageable pageable) {
        UUID merchantId = merchantResolver.resolveCallerMerchantId();
        Page<PaymentResponse> page = paymentRepository.findByMerchantId(merchantId, pageable).map(paymentMapper::toResponse);
        return PageResponse.of(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements());
    }

    /**
     * Shared shape for authorize/capture/void/refund: resolve merchant, guard with
     * idempotency, load the caller's own payment inside the transaction, apply the
     * given state mutation, publish the resulting event, record the idempotent
     * response. {@code mutation} both mutates {@code payment} and returns the event
     * type name to publish — it knows the resulting status, this method doesn't need to.
     */
    private PaymentResponse mutate(UUID paymentId, String idempotencyKey, String operation, Object requestBody,
                                   Function<Payment, String> mutation) {
        requireIdempotencyKey(idempotencyKey);
        UUID merchantId = merchantResolver.resolveCallerMerchantId();
        String fingerprint = idempotencyService.fingerprint(
                "POST:/api/v1/payments/" + paymentId + "/" + operation, requestBody);

        return idempotencyService.guarded(merchantId, idempotencyKey, fingerprint, PaymentResponse.class, () ->
                transactionTemplate.execute(status -> {
                    Payment payment = getOwnedPayment(paymentId, merchantId);
                    PaymentStatus previous = payment.getStatus();
                    String eventType = mutation.apply(payment);
                    eventPublisher.publish(payment, eventType, previous);
                    PaymentResponse response = paymentMapper.toResponse(payment);
                    idempotencyService.record(merchantId, idempotencyKey, fingerprint, 200, response);
                    return response;
                }));
    }

    private Payment getOwnedPayment(UUID paymentId, UUID merchantId) {
        return paymentRepository.findByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> ResourceNotFoundException.of("Payment", paymentId));
    }

    private static void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key header is required.");
        }
    }
}
