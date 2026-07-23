package com.paymentflow.sandbox.engine;

import com.paymentflow.sandbox.domain.OverrideScenario;

/**
 * An already-resolved, already-active override, as the engine needs it. Resolving
 * whether an override is active (not expired, not exhausted) is the override service's
 * job (M17.5) — the engine itself has no notion of counters or expiry, only of "this
 * override is in effect right now."
 *
 * <p>{@code valueMs} is contextual per scenario: the response delay for
 * {@link OverrideScenario#INJECT_LATENCY}, unused for {@link OverrideScenario#FORCE_TIMEOUT}
 * (which always uses the platform's maximum injectable latency, regardless of this
 * value), and the deferred-capture delay for {@link OverrideScenario#DELAY_SETTLEMENT}.
 */
public record OverrideSnapshot(
        OverrideScenario scenario,
        String declineCode,
        String errorCode,
        Integer valueMs) {
}
