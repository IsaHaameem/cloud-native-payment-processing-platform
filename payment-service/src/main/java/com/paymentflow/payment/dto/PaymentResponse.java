package com.paymentflow.payment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

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
        UUID id,
        String object,
        UUID merchantId,
        String mode,
        long amountMinor,
        String currency,
        String status,
        long capturedAmountMinor,
        long refundedAmountMinor,
        String description,
        String paymentMethodToken,
        String failureReason,
        Map<String, String> metadata,
        List<RefundResponse> refunds,
        Instant createdAt,
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
