package dev.paymentflow.model;

import java.util.List;
import java.util.Map;

/**
 * A payment.
 *
 * <p>{@code status} is lowercase {@code snake_case} as of API revision {@code 2026-08-01}
 * ({@code created}, {@code authorized}, {@code captured}, {@code partially_refunded},
 * {@code refunded}, {@code failed}, {@code voided}); a caller pinned to {@code 2026-07-27}
 * receives the upper-case spelling. New values may be added without a new revision — treat an
 * unrecognised status as one you do not handle.
 *
 * <p>{@code failureReason} is the acquirer's own reason, present only when {@code status} is
 * {@code failed}. {@code refunds} is present <b>only</b> when you ask with {@code expand=refunds}
 * and is omitted — not empty — otherwise. {@code mode} is fixed by the API key that created the
 * payment and cannot be changed by any header, parameter or field.
 */
public record PaymentResponse(
        Long amountMinor,
        Long capturedAmountMinor,
        String createdAt,
        String currency,
        String description,
        String failureReason,
        String id,
        String merchantId,
        Map<String, String> metadata,
        String mode,
        String object,
        String paymentMethodToken,
        Long refundedAmountMinor,
        List<RefundResponse> refunds,
        String status,
        String updatedAt) {}
