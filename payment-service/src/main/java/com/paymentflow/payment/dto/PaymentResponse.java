package com.paymentflow.payment.dto;

import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID merchantId,
        long amountMinor,
        String currency,
        String status,
        long capturedAmountMinor,
        long refundedAmountMinor,
        String description,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {
}
