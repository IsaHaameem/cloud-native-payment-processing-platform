package com.paymentflow.sandbox.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * The decision-log query API's response shape (§4.2/M17.8) — "payment-service learns
 * the verdict; sandbox keeps the reasoning." {@code overrideId} is a bare UUID
 * reference (not the override's own contents) — enough for a caller to know a
 * simulation override was involved without this endpoint re-exposing
 * {@code simulation_overrides}' own control-API surface.
 */
public record DecisionLogEntryResponse(
        @Schema(description = """
                The idempotency key this decision was made under. Re-deciding with the same \
                key returns the original verdict rather than deciding again, so a retry \
                cannot change an outcome your integration already saw.""")
        String decisionKey,

        @Schema(description = "The payment this decision was made about.")
        UUID paymentId,

        @Schema(description = "Which operation was being decided — an authorization, a "
                + "capture, a refund.", example = "AUTHORIZE")
        String operation,

        @Schema(description = "What the simulated acquirer decided.", example = "APPROVE")
        String outcome,

        @Schema(description = "The decline code returned, when the outcome was a decline.",
                example = "insufficient_funds")
        String declineCode,

        @Schema(description = "The error code returned, when the outcome was an error.",
                example = "processor_unavailable")
        String errorCode,

        @Schema(description = "How long the simulated acquirer took to answer, in "
                + "milliseconds.", example = "0")
        int latencyMs,

        @Schema(description = """
                **Why the outcome was what it was**: the test card's own catalogue entry, an \
                active simulation override, or the mode's default. This is the field that \
                turns a surprising result into an explained one.""",
                example = "TEST_CARD")
        String source,

        @Schema(description = """
                The simulation override that produced this decision, when `source` names one. \
                A bare reference rather than the override's contents — enough to know one was \
                involved without this endpoint re-exposing the simulation control surface.""")
        UUID overrideId,

        @Schema(description = "The operation whose outcome was deferred, when the decision "
                + "scheduled one for later.", example = "CAPTURE")
        String deferredOperation,

        @Schema(description = "How long the deferred outcome was scheduled to wait, in "
                + "milliseconds.", example = "5000")
        Integer deferredDelayMs,

        @Schema(description = "When the decision was made, as RFC 3339.")
        Instant createdAt) {
}
