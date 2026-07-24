package com.paymentflow.sandbox.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code enactedFrom} is {@code null} for the six scenarios {@code DecisionEngine}
 * actually reasons about, and {@code "M18"} for {@code DUPLICATE_WEBHOOKS}/
 * {@code WEBHOOK_FAILURE} (D131) — an honest marker that the override is stored and
 * validated now but not yet acted on by anything.
 */
public record SimulationOverrideResponse(
        UUID id,
        String scenario,
        String declineCode,
        String errorCode,
        Integer latencyMs,
        Integer remainingCount,
        Instant expiresAt,
        String enactedFrom) {
}
