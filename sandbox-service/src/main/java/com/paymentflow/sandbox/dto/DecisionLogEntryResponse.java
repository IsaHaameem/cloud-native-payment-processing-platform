package com.paymentflow.sandbox.dto;

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
        String decisionKey,
        UUID paymentId,
        String operation,
        String outcome,
        String declineCode,
        String errorCode,
        int latencyMs,
        String source,
        UUID overrideId,
        String deferredOperation,
        Integer deferredDelayMs,
        Instant createdAt) {
}
