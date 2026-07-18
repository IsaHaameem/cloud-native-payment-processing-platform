package com.paymentflow.payment.web;

import com.paymentflow.common.dto.page.PageResponse;
import com.paymentflow.payment.dto.CreatePaymentRequest;
import com.paymentflow.payment.dto.PaymentResponse;
import com.paymentflow.payment.dto.RefundRequest;
import com.paymentflow.payment.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Payment lifecycle: create → authorize → capture → refund/void. Every mutating
 * endpoint requires {@code Idempotency-Key} (validated in the service layer, not via
 * {@code @RequestHeader(required=true)} — a missing *header* throws
 * {@code MissingRequestHeaderException}, which common-lib's exception handler doesn't
 * map, and would otherwise leak as a raw 500).
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentResponse create(@Valid @RequestBody CreatePaymentRequest request,
                                  @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return paymentService.create(request, idempotencyKey);
    }

    @PostMapping("/{id}/authorize")
    public PaymentResponse authorize(@PathVariable UUID id,
                                     @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return paymentService.authorize(id, idempotencyKey);
    }

    @PostMapping("/{id}/capture")
    public PaymentResponse capture(@PathVariable UUID id,
                                   @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return paymentService.capture(id, idempotencyKey);
    }

    @PostMapping("/{id}/refund")
    public PaymentResponse refund(@PathVariable UUID id,
                                  @Valid @RequestBody(required = false) RefundRequest request,
                                  @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return paymentService.refund(id, request, idempotencyKey);
    }

    @PostMapping("/{id}/void")
    public PaymentResponse voidPayment(@PathVariable UUID id,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return paymentService.voidPayment(id, idempotencyKey);
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable UUID id) {
        return paymentService.get(id);
    }

    @GetMapping
    public PageResponse<PaymentResponse> list(Pageable pageable) {
        return paymentService.list(pageable);
    }
}
