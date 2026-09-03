package dev.paymentflow.model;

import java.util.Map;

/**
 * A refund issued against a payment. Created through {@code client.payments().refund(...)}, which
 * returns the <b>payment</b> — the refund is the newest entry in its {@code refunds} array.
 * {@code status} follows the same {@code snake_case}/revision rule as {@link PaymentResponse}.
 */
public record RefundResponse(
        Long amountMinor,
        String createdAt,
        String currency,
        String failureReason,
        String id,
        String merchantId,
        Map<String, String> metadata,
        String mode,
        String object,
        String paymentId,
        String reason,
        String status,
        String updatedAt) {}
