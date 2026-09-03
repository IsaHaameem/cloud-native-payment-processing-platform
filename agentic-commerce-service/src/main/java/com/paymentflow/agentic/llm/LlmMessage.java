package com.paymentflow.agentic.llm;

import java.util.List;
import java.util.Objects;

/**
 * One turn of the conversation as the model sees it.
 *
 * <p>Provider-neutral by construction. Anthropic represents a tool result as a block inside a
 * <em>user</em> message; another provider may give it a role of its own. Modelling the three
 * roles explicitly here means that difference lives in one adapter rather than leaking into
 * the runtime, which is the whole point of the abstraction.
 *
 * <p><b>Nothing in this record is authoritative about money.</b> A conversation is context, not
 * a ledger: an assistant turn saying "your payment succeeded" is a sentence the model produced,
 * and the runtime re-reads server-side state before every financial action rather than
 * believing it. See {@code AgentRuntime}.
 *
 * @param text        the visible text of the turn. Redacted before it is ever persisted
 * @param toolCalls   the tools the model asked for, on an {@link Role#ASSISTANT} turn
 * @param toolResults the structured results handed back, on a {@link Role#TOOL} turn
 */
public record LlmMessage(Role role, String text, List<LlmToolCall> toolCalls, List<LlmToolResult> toolResults) {

    public enum Role {
        USER,
        ASSISTANT,
        /** Tool results being returned to the model. Mapped onto whatever shape the provider uses. */
        TOOL
    }

    public LlmMessage {
        Objects.requireNonNull(role, "role");
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        toolResults = toolResults == null ? List.of() : List.copyOf(toolResults);
    }

    public static LlmMessage user(String text) {
        return new LlmMessage(Role.USER, text, List.of(), List.of());
    }

    public static LlmMessage assistant(String text, List<LlmToolCall> toolCalls) {
        return new LlmMessage(Role.ASSISTANT, text, toolCalls, List.of());
    }

    /**
     * The results of one assistant turn's tool calls, returned together.
     *
     * <p>Together deliberately: a provider that receives parallel tool calls expects every
     * result in a single turn, and splitting them teaches the model to stop calling tools in
     * parallel.
     */
    public static LlmMessage toolResults(List<LlmToolResult> results) {
        return new LlmMessage(Role.TOOL, null, List.of(), results);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
