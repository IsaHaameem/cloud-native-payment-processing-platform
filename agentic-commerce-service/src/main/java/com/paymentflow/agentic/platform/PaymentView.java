package com.paymentflow.agentic.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * A payment as this service models it — the projection of the platform's {@code PaymentResponse}
 * that the agent is allowed to reason about.
 *
 * <p>Narrower than the wire shape on purpose. {@code merchantId} is context the caller already
 * is, and {@code metadata} is merchant-controlled free-form text with no defined meaning —
 * putting arbitrary merchant text into a model's context window is how a payment record becomes
 * a prompt-injection surface. The same reasoning {@code ProductView} applies to the catalogue.
 *
 * <p><b>Everything here is the platform's answer, and none of it is the model's.</b> When the
 * agent tells a buyer what a payment's status or captured amount is, the number came through
 * this record from a {@code GET} the platform served. There is no code path in which the agent
 * reports a payment state it inferred rather than read.
 *
 * <p>{@code status} is lowercase {@code snake_case} because {@link PaymentFlowClient} pins the
 * API revision that spells it that way. Unrecognised values are carried through as-is: the
 * contract says new statuses may be added without a new revision, so treating an unfamiliar one
 * as an error would break this service on a platform change that broke nothing else.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentView(
        String id,
        String status,
        long amountMinor,
        long capturedAmountMinor,
        long refundedAmountMinor,
        String currency,
        String description,
        String failureReason,
        String paymentMethodToken,
        String mode,
        Instant createdAt,
        Instant updatedAt) {

    /** The statuses this service acts on. Anything else is reported to the buyer, not interpreted. */
    public static final String STATUS_CREATED = "created";
    public static final String STATUS_AUTHORIZED = "authorized";
    public static final String STATUS_CAPTURED = "captured";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_REFUNDED = "refunded";
    public static final String STATUS_PARTIALLY_REFUNDED = "partially_refunded";

    public boolean isAuthorized() {
        return STATUS_AUTHORIZED.equals(status);
    }

    public boolean isCaptured() {
        return STATUS_CAPTURED.equals(status);
    }

    public boolean isFailed() {
        return STATUS_FAILED.equals(status);
    }

    /** How much could still be refunded, per the platform's own numbers. Never negative. */
    public long refundableMinor() {
        return Math.max(0, capturedAmountMinor - refundedAmountMinor);
    }
}
