package com.paymentflow.sandbox.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code enactedFrom} is {@code null} for the six scenarios {@code DecisionEngine}
 * actually reasons about, and {@code "M18"} for {@code DUPLICATE_WEBHOOKS}/
 * {@code WEBHOOK_FAILURE} (D131) — an honest marker that the override is stored and
 * validated now but not yet acted on by anything.
 */
public record SimulationOverrideResponse(
        @Schema(description = "Unique identifier for this override.")
        UUID id,

        @Schema(description = "The behaviour being forced.", example = "FORCE_DECLINE")
        String scenario,

        // This record carries no @JsonInclude(NON_NULL), so the fields that do not apply to
        // the active scenario are serialized as explicit nulls rather than omitted. The
        // document declared them non-null until M21.7's contract test read a real response.
        @Schema(description = "The decline code being returned, for a decline scenario. "
                + "**Null** otherwise.",
                types = {"string", "null"}, example = "insufficient_funds")
        String declineCode,

        @Schema(description = "The error code being returned, for an error scenario. **Null** "
                + "otherwise.",
                types = {"string", "null"}, example = "processor_unavailable")
        String errorCode,

        @Schema(description = "The latency being injected, in milliseconds. **Null** for a "
                + "scenario that injects none.",
                types = {"integer", "null"}, format = "int32", example = "3000")
        Integer latencyMs,

        @Schema(description = "How many authorizations the override still applies to. Counts "
                + "down as it is used; **null** for a time-bounded override.",
                types = {"integer", "null"}, format = "int32", example = "2")
        Integer remainingCount,

        @Schema(description = "When the override stops applying. **Null** for a count-bounded "
                + "override.",
                types = {"string", "null"}, format = "date-time")
        Instant expiresAt,

        @Schema(description = """
                Which part of the platform acts on this scenario. **`null` means the decision \
                engine enforces it now**; a value names the release that will. The webhook \
                scenarios are stored and validated but not yet acted on, and saying so here \
                is more honest than accepting an override that silently does nothing.""",
                types = {"string", "null"}, example = "M18")
        String enactedFrom) {
}
