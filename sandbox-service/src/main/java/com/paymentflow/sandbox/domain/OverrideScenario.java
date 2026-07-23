package com.paymentflow.sandbox.domain;

/**
 * The override vocabulary (§8.2). Persisted overrides don't exist until M17.5
 * ({@code simulation_overrides}); the enum is introduced here in M17.2 so
 * {@link com.paymentflow.sandbox.engine.DecisionEngine}'s precedence logic — and its
 * exhaustive unit tests — can be written and verified against directly-constructed
 * override values before persistence exists, and M17.5 only has to wire a real lookup
 * behind the same engine input.
 *
 * <p>{@code DUPLICATE_WEBHOOKS} and {@code WEBHOOK_FAILURE} are deliberately absent —
 * D131: those two scenarios are webhook-delivery concerns the engine never reasons
 * about (they're stored and validated at the control-API layer in M17.5, then enacted
 * by M18's delivery pipeline, which is the only thing that ever reads them).
 */
public enum OverrideScenario {
    /** Authorize-time only: declines every request with the configured code. */
    FORCE_DECLINE,
    /** Authorize-time only: errors every request with the configured code. */
    FORCE_ERROR,
    /** Authorize-time only: approves, but with the configured response delay. */
    INJECT_LATENCY,
    /** Authorize-time only: forces a {@code processing_error} at the platform's maximum injectable latency, guaranteeing the caller's own timeout fires first. */
    FORCE_TIMEOUT,
    /** Authorize-time only: errors every request with {@code rate_limited}. */
    FORCE_RATE_LIMIT,
    /** Capture-time only: defers capture settlement by the configured delay, exactly like {@code pm_card_delayedSettlement}. */
    DELAY_SETTLEMENT
}
