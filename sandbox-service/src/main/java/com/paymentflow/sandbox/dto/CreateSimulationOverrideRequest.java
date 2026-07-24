package com.paymentflow.sandbox.dto;

import com.paymentflow.sandbox.domain.SimulationScenario;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Scenario-specific requiredness (declineCode for {@code FORCE_DECLINE}, errorCode for
 * {@code FORCE_ERROR}, latencyMs for {@code INJECT_LATENCY}/{@code DELAY_SETTLEMENT})
 * and the "at least one of remainingCount/durationSeconds" rule aren't expressible as
 * plain bean-validation annotations across fields — {@code OverrideService} validates
 * them, the same split {@code SandboxDecisionRequest} already uses for its own
 * cross-field rules.
 */
public record CreateSimulationOverrideRequest(
        @NotNull SimulationScenario scenario,
        @Size(max = 48) String declineCode,
        @Size(max = 48) String errorCode,
        Integer latencyMs,
        @Positive Integer remainingCount,
        @Positive Integer durationSeconds) {
}
