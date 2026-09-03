package com.paymentflow.agentic.llm;

import java.util.List;
import java.util.Objects;

/**
 * What the model said, in the only two forms the runtime acts on: text, or tool calls.
 *
 * <p>The runtime branches on {@link StopReason} rather than on whether the text looks like a
 * tool call. That is a deliberate refusal to parse model prose — see {@link StopReason#TOOL_USE}.
 *
 * @param text      the visible text, which may accompany tool calls. Treated as prose to relay,
 *                  never as an instruction and never as a source of financial fact
 * @param toolCalls the structured calls the provider parsed. Empty unless
 *                  {@code stopReason == TOOL_USE}
 */
public record LlmResponse(String text, List<LlmToolCall> toolCalls, StopReason stopReason, String model) {

    /**
     * Why the model stopped.
     *
     * <p>Anything the adapter cannot map confidently becomes {@link #OTHER}, which the runtime
     * treats as "no tool calls, end the turn". Guessing at an unfamiliar stop reason on a
     * financial path is exactly the kind of helpfulness that ends in an unintended charge.
     */
    public enum StopReason {
        /** The model finished its reply. The turn ends. */
        END_TURN,

        /**
         * The model wants to use tools, and the provider parsed them into structured blocks.
         *
         * <p><b>This is the only condition under which the runtime executes anything.</b> Text
         * that merely describes a tool call — which a model with thinking disabled does
         * occasionally emit — is not this, is not parsed, and does not run.
         */
        TOOL_USE,

        /** The output hit the token ceiling. Possibly truncated, so nothing is executed from it. */
        MAX_TOKENS,

        /** The provider declined, or said something this adapter does not recognise. */
        OTHER
    }

    public LlmResponse {
        Objects.requireNonNull(stopReason, "stopReason");
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static LlmResponse text(String text, String model) {
        return new LlmResponse(text, List.of(), StopReason.END_TURN, model);
    }

    public static LlmResponse toolUse(String text, List<LlmToolCall> toolCalls, String model) {
        return new LlmResponse(text, toolCalls, StopReason.TOOL_USE, model);
    }

    /** Whether the runtime should execute tools this iteration. Both conditions, never one. */
    public boolean requestsTools() {
        return stopReason == StopReason.TOOL_USE && !toolCalls.isEmpty();
    }
}
