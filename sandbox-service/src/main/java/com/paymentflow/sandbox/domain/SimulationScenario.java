package com.paymentflow.sandbox.domain;

import java.util.Optional;

/**
 * The full control-API override vocabulary (§8.2, M17.5) — wider than
 * {@link OverrideScenario}'s six engine-relevant values: {@code DUPLICATE_WEBHOOKS} and
 * {@code WEBHOOK_FAILURE} are accepted, validated, and persisted here, but
 * {@link #toEngineScenario()} deliberately has no mapping for them (D131) — the engine
 * never reasons about webhook delivery, only M18's delivery pipeline ever reads those
 * two rows.
 */
public enum SimulationScenario {
    FORCE_DECLINE,
    FORCE_ERROR,
    INJECT_LATENCY,
    FORCE_TIMEOUT,
    FORCE_RATE_LIMIT,
    DELAY_SETTLEMENT,
    DUPLICATE_WEBHOOKS,
    WEBHOOK_FAILURE;

    /** Empty for the two webhook-path scenarios — they never reach {@link com.paymentflow.sandbox.engine.DecisionEngine}. */
    public Optional<OverrideScenario> toEngineScenario() {
        return switch (this) {
            case FORCE_DECLINE -> Optional.of(OverrideScenario.FORCE_DECLINE);
            case FORCE_ERROR -> Optional.of(OverrideScenario.FORCE_ERROR);
            case INJECT_LATENCY -> Optional.of(OverrideScenario.INJECT_LATENCY);
            case FORCE_TIMEOUT -> Optional.of(OverrideScenario.FORCE_TIMEOUT);
            case FORCE_RATE_LIMIT -> Optional.of(OverrideScenario.FORCE_RATE_LIMIT);
            case DELAY_SETTLEMENT -> Optional.of(OverrideScenario.DELAY_SETTLEMENT);
            case DUPLICATE_WEBHOOKS, WEBHOOK_FAILURE -> Optional.empty();
        };
    }

    public boolean isWebhookScenario() {
        return this == DUPLICATE_WEBHOOKS || this == WEBHOOK_FAILURE;
    }
}
