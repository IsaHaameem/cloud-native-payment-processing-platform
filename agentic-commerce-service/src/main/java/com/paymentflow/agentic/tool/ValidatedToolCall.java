package com.paymentflow.agentic.tool;

import java.util.Collections;
import java.util.Map;

/**
 * A tool call that has passed schema validation, paired with the tool that validated it.
 *
 * <p>Its real job is to be the <b>one place in this service where a generic tool is cast to
 * its own input type</b>. {@link ToolRegistry} holds {@code AgentTool<?>} because it holds
 * many tools with different inputs; the orchestrator needs to call {@code resolve} and
 * {@code execute} with the typed input that tool's own {@code validate} produced. That cast is
 * safe exactly once — here, where the input demonstrably came from this tool — and confining
 * it to one class means no call site elsewhere has to be trusted to get it right.
 *
 * <p>It is also what keeps the orchestrator free of a switch on the tool name. Adding a tool
 * is adding a {@code @Component}; nothing in the pipeline enumerates them.
 */
public final class ValidatedToolCall {

    private final AgentTool<Object> tool;
    private final Object input;
    private final Map<String, Object> arguments;

    @SuppressWarnings("unchecked")
    ValidatedToolCall(AgentTool<?> tool, Object input, Map<String, Object> arguments) {
        // Safe by construction: `input` is the return value of this same tool's validate().
        // ToolRegistry is the only caller, and it never pairs an input with a different tool.
        this.tool = (AgentTool<Object>) tool;
        this.input = input;
        // Wraps, not copies: ToolRegistry.validate() already handed us a defensive copy of
        // whatever the caller passed in, so this map is already ours alone. Wrapping it — rather
        // than trusting every future reader of arguments() to treat a live Map as read-only —
        // is what makes that "alone" durable: nothing reachable from this object can put a
        // second reference to a mutable map into a caller's hands.
        this.arguments = Collections.unmodifiableMap(arguments);
    }

    public ToolSpec spec() {
        return tool.spec();
    }

    public String toolName() {
        return tool.spec().name();
    }

    /**
     * The validated arguments, for the action log's redacted summary.
     *
     * <p>Unmodifiable: a mutation attempt throws {@link UnsupportedOperationException} rather
     * than silently changing what this call is a record of.
     */
    public Map<String, Object> arguments() {
        return arguments;
    }

    /** Reads server-side facts. No money moves, and the policy engine has not yet spoken. */
    public ResolvedAction resolve(ToolContext context) {
        return tool.resolve(context, input);
    }

    /** Performs the action. Reachable only after {@code PERMIT}, and after approval if required. */
    public ToolResult execute(ToolContext context, ResolvedAction resolved) {
        return tool.execute(context, input, resolved);
    }
}
