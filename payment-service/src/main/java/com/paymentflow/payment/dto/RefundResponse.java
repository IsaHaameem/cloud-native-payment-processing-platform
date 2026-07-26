package com.paymentflow.payment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The refund resource (M19.3) — first-class, with its own id, and the same field
 * conventions as {@link PaymentResponse}: an {@code object} discriminator, camelCase,
 * and {@code metadata} that is an empty object rather than null when unset.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefundResponse(
        UUID id,
        String object,
        UUID paymentId,
        UUID merchantId,
        String mode,
        long amountMinor,
        String currency,
        String status,
        String reason,
        String failureReason,
        Map<String, String> metadata,
        Instant createdAt,
        Instant updatedAt) {

    /** The discriminator carried by every refund object. */
    public static final String OBJECT_TYPE = "refund";

    public RefundResponse {
        metadata = (metadata == null) ? Map.of() : Map.copyOf(metadata);
    }
}
