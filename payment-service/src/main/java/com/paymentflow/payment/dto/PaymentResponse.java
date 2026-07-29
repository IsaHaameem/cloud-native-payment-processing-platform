package com.paymentflow.payment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.paymentflow.common.openapi.PublicApiParameters;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The payment resource, served by both API tiers (D98) — the internal {@code /api/v1}
 * controller and M19's public {@code /v1} one. One shape, so a field can never mean two
 * different things depending on which door it came through.
 *
 * <p>M19 adds three fields, all additive and therefore non-breaking (§4.10): the
 * {@code object} discriminator every V2 resource carries, {@code metadata}, and
 * {@code refunds} — the last populated only when the caller asks via
 * {@code expand=refunds}, and omitted entirely otherwise rather than returned as an
 * empty list, which would be indistinguishable from "this payment has no refunds".
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentResponse(
        @Schema(description = "Unique identifier for this payment.")
        UUID id,

        @Schema(description = "Always `payment`. The discriminator that identifies this "
                + "object out of context.", example = "payment")
        String object,

        @Schema(description = "The merchant this payment belongs to — always your own "
                + "account.")
        UUID merchantId,

        @Schema(description = """
                Whether this payment is `test` or `live` data. Determined by the API key \
                that created it and not changeable by any header, parameter or field.""",
                allowableValues = {"test", "live"}, example = "test")
        String mode,

        @Schema(description = """
                The amount authorized, as an integer in the currency's minor unit: `1000` \
                in `USD` is $10.00.""", example = "1000")
        long amountMinor,

        @Schema(description = "The three-letter ISO 4217 currency code.", example = "USD")
        String currency,

        @Schema(description = """
                Where this payment is in its lifecycle. Lowercase `snake_case` as of API \
                revision `2026-08-01`; callers pinned to `2026-07-27` receive the older \
                upper-case spelling. **New values may be added without a new revision**, so \
                treat an unrecognised status as one you do not handle rather than as an \
                error.""",
                example = "authorized")
        String status,

        @Schema(description = "How much of the authorized amount has been captured so far, "
                + "in minor units.", example = "1000")
        long capturedAmountMinor,

        @Schema(description = "How much has been refunded so far, in minor units. Never "
                + "exceeds `capturedAmountMinor`.", example = "0")
        long refundedAmountMinor,

        @Schema(description = "The description supplied when the payment was created.",
                example = "Order A-1234")
        String description,

        @Schema(description = "The payment-method token this payment authorizes against, if "
                + "one was supplied.", example = "tok_visa_approved")
        String paymentMethodToken,

        @Schema(description = """
                Why the payment failed, when `status` is `failed`. Absent otherwise — this \
                is the acquirer's reason, not a validation message.""",
                example = "card_declined")
        String failureReason,

        @Schema(description = PublicApiParameters.METADATA_FIELD)
        Map<String, String> metadata,

        @Schema(description = """
                The refunds issued against this payment. **Present only when you ask for it** \
                with `?expand=refunds`, and omitted entirely otherwise rather than returned \
                empty — an empty list would be indistinguishable from "this payment has no \
                refunds".""")
        List<RefundResponse> refunds,

        @Schema(description = "When the payment was created, as RFC 3339.")
        Instant createdAt,

        @Schema(description = "When the payment last changed, as RFC 3339.")
        Instant updatedAt) {

    /** The discriminator carried by every payment object. */
    public static final String OBJECT_TYPE = "payment";

    public PaymentResponse {
        metadata = (metadata == null) ? Map.of() : Map.copyOf(metadata);
        refunds = (refunds == null) ? null : List.copyOf(refunds);
    }

    /** The same payment with its refunds attached — the {@code expand=refunds} form. */
    public PaymentResponse withRefunds(List<RefundResponse> expanded) {
        return new PaymentResponse(id, object, merchantId, mode, amountMinor, currency, status, capturedAmountMinor,
                refundedAmountMinor, description, paymentMethodToken, failureReason, metadata, expanded,
                createdAt, updatedAt);
    }
}
