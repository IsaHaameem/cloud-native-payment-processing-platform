package com.paymentflow.agentic.llm;

import java.util.Objects;

/**
 * What actually happened, handed back to the model.
 *
 * <p>{@code content} is a JSON rendering of a {@code ToolResult} — a record this service
 * defined, holding fields this service chose. It is never a raw provider payload and never an
 * exception's stack trace.
 *
 * <p>{@code error} is set for every non-success outcome: a schema rejection, a policy refusal,
 * an approval requirement, a decline. Marking them as errors rather than passing them off as
 * ordinary results is what stops the model narrating a refusal as a success — it has to
 * account for the failure in its reply because the provider surfaces it as one.
 *
 * @param toolCallId the id of the {@link LlmToolCall} this answers. A result with no matching
 *                   call is a protocol error, not something to guess at
 */
public record LlmToolResult(String toolCallId, String toolName, String content, boolean error) {

    public LlmToolResult {
        Objects.requireNonNull(toolCallId, "toolCallId");
        Objects.requireNonNull(toolName, "toolName");
    }

    public static LlmToolResult ok(String toolCallId, String toolName, String content) {
        return new LlmToolResult(toolCallId, toolName, content, false);
    }

    public static LlmToolResult failure(String toolCallId, String toolName, String content) {
        return new LlmToolResult(toolCallId, toolName, content, true);
    }
}
