package com.paymentflow.sandbox.dto;

import com.paymentflow.sandbox.domain.SimulationScenario;
import io.swagger.v3.oas.annotations.media.Schema;
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
        @Schema(description = """
                Which behaviour to force. The scenario decides which of the fields below are \
                required: `FORCE_DECLINE` needs `declineCode`, `FORCE_ERROR` needs \
                `errorCode`, and the latency scenarios need `latencyMs`.""",
                example = "FORCE_DECLINE")
        @NotNull SimulationScenario scenario,

        @Schema(description = "The decline code to return. Required for `FORCE_DECLINE`, "
                + "ignored otherwise.", example = "insufficient_funds")
        @Size(max = 48) String declineCode,

        @Schema(description = "The error code to fail with. Required for `FORCE_ERROR`, "
                + "ignored otherwise.", example = "processor_unavailable")
        @Size(max = 48) String errorCode,

        @Schema(description = "How long to stall, in milliseconds. Required for the latency "
                + "scenarios, ignored otherwise.", example = "3000")
        Integer latencyMs,

        @Schema(description = """
                How many authorizations the override applies to before it expires. **Supply \
                this or `durationSeconds`** — an override with neither would never stop, and \
                a sandbox you cannot get back out of is worse than one you cannot get into.""",
                example = "3")
        @Positive Integer remainingCount,

        @Schema(description = "How long the override lasts, in seconds. The alternative to "
                + "`remainingCount`; supply one of the two.", example = "300")
        @Positive Integer durationSeconds) {
}
