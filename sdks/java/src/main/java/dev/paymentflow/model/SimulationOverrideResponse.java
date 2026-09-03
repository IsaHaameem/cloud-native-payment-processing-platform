package dev.paymentflow.model;

/**
 * An active sandbox simulation override. Several fields are <b>explicitly {@code null}</b> rather
 * than absent: {@code declineCode}/{@code errorCode}/{@code latencyMs} for a scenario that does
 * not use them, {@code expiresAt} for a count-bounded override, {@code remainingCount} for a
 * time-bounded one, and {@code enactedFrom} when the decision engine enforces the scenario now
 * (a value names the release that will). Test mode only.
 */
public record SimulationOverrideResponse(
        String declineCode,
        String enactedFrom,
        String errorCode,
        String expiresAt,
        String id,
        Long latencyMs,
        Long remainingCount,
        String scenario) {}
