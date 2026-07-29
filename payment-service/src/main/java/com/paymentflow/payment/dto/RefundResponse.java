package com.paymentflow.payment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.paymentflow.common.openapi.PublicApiParameters;
import io.swagger.v3.oas.annotations.media.Schema;

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
        @Schema(description = "Unique identifier for this refund.")
        UUID id,

        @Schema(description = "Always `refund`. The discriminator that identifies this object "
                + "out of context.", example = "refund")
        String object,

        @Schema(description = "The payment this refund was issued against.")
        UUID paymentId,

        @Schema(description = "The merchant this refund belongs to — always your own account.")
        UUID merchantId,

        @Schema(description = "Whether this refund is `test` or `live` data. Inherited from "
                + "the payment.", allowableValues = {"test", "live"}, example = "test")
        String mode,

        @Schema(description = "The amount refunded, in the currency's minor unit.",
                example = "1000")
        long amountMinor,

        @Schema(description = "The three-letter ISO 4217 currency code, matching the payment's.",
                example = "USD")
        String currency,

        @Schema(description = """
                Where this refund is in its lifecycle. Lowercase `snake_case` as of API \
                revision `2026-08-01`. New values may be added without a new revision.""",
                example = "succeeded")
        String status,

        @Schema(description = "The reason supplied when the refund was issued, if any.",
                example = "Customer returned the item")
        String reason,

        @Schema(description = "Why the refund failed, when `status` is `failed`. Absent "
                + "otherwise.")
        String failureReason,

        @Schema(description = PublicApiParameters.METADATA_FIELD)
        Map<String, String> metadata,

        @Schema(description = "When the refund was created, as RFC 3339.")
        Instant createdAt,

        @Schema(description = "When the refund last changed, as RFC 3339.")
        Instant updatedAt) {

    /** The discriminator carried by every refund object. */
    public static final String OBJECT_TYPE = "refund";

    public RefundResponse {
        metadata = (metadata == null) ? Map.of() : Map.copyOf(metadata);
    }
}
