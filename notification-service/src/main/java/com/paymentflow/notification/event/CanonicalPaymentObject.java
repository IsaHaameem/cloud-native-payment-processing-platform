package com.paymentflow.notification.event;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * The merchant-facing shape of a payment inside a webhook body (M18.3, §4.5) — the
 * {@code data.object} of a {@link WebhookEventBody}, and the same object M19's Events
 * API will serve.
 *
 * <p>Deliberately a translation of {@link PaymentNotificationEventPayload} rather than
 * that record serialized directly. The internal payload carries fields that exist for
 * this platform's own consumers and are not promises to a merchant —
 * {@code merchantContactEmail} and {@code merchantWebhookUrl} are routing data embedded
 * by D43, and echoing a merchant's own contact email back inside every webhook body
 * would be a gratuitous data exposure to whatever endpoint happens to receive it. This
 * class is where that boundary is drawn, once.
 *
 * <p>{@code object} is a constant discriminator (§5/M19's "{@code object} discriminator"
 * convention) so a client deserializing a heterogeneous event stream can branch on the
 * payload type without inspecting the event name.
 *
 * <p>Field names are camelCase, matching every other response this platform emits.
 * M21 owns the public contract freeze; this is not the milestone to introduce a second
 * naming convention.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CanonicalPaymentObject(
        UUID id,
        String object,
        UUID merchantId,
        long amountMinor,
        String currency,
        String status,
        String previousStatus,
        /**
         * The amount this specific transition moved — the full amount for
         * authorize/capture/void, but the *incremental* amount for a partial refund. A
         * merchant reconciling from webhooks needs the delta, not the running total, for
         * the same reason transaction-service's ledger does (see
         * {@code PaymentEventPayload}).
         */
        long eventAmountMinor,
        String mode) {

    /** The discriminator value carried by every payment object. */
    public static final String OBJECT_TYPE = "payment";

    public static CanonicalPaymentObject from(PaymentNotificationEventPayload payload, String mode) {
        return new CanonicalPaymentObject(
                payload.paymentId(),
                OBJECT_TYPE,
                payload.merchantId(),
                payload.amountMinor(),
                payload.currency(),
                payload.status(),
                payload.previousStatus(),
                payload.eventAmountMinor(),
                mode);
    }
}
