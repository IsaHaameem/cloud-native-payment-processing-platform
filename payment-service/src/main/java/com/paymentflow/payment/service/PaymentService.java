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
import com.paymentflow.payment.merchant.MerchantSummary;
import com.paymentflow.payment.mode.RequestModeResolver;
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
    private final RequestModeResolver requestModeResolver;
    private final TransactionTemplate transactionTemplate;

    public PaymentService(PaymentRepository paymentRepository, PaymentMapper paymentMapper,
                          PaymentEventPublisher eventPublisher, IdempotencyService idempotencyService,
                          MerchantResolver merchantResolver, RequestModeResolver requestModeResolver,
                          TransactionTemplate transactionTemplate) {
        this.paymentRepository = paymentRepository;
        this.paymentMapper = paymentMapper;
        this.eventPublisher = eventPublisher;
        this.idempotencyService = idempotencyService;
        this.merchantResolver = merchantResolver;
        this.requestModeResolver = requestModeResolver;
        this.transactionTemplate = transactionTemplate;
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
                            merchantId, mode, request.amountMinor(), request.currency(), request.description()));
                    eventPublisher.publish(payment, "PaymentCreated", null, payment.getAmountMinor(), merchant);
                    PaymentResponse response = paymentMapper.toResponse(payment);
                    idempotencyService.record(merchantId, mode, idempotencyKey, fingerprint, 201, response);
                    return response;
                }));
    }

    public PaymentResponse authorize(UUID paymentId, String idempotencyKey) {
        return mutate(paymentId, idempotencyKey, "authorize", null, payment -> {
            payment.authorize();
            return new MutationOutcome("PaymentAuthorized", payment.getAmountMinor());
        });
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
            payment.refund(amount);
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
