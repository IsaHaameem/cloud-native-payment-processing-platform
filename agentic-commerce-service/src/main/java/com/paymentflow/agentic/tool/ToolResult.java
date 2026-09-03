package com.paymentflow.agentic.tool;

import com.paymentflow.agentic.action.Redactor;

import java.util.Objects;

/**
 * The structured outcome of one tool call, as the model is shown it.
 *
 * <p><b>A failure is a result, not an exception the model never hears about.</b> A declined
 * payment, a refused policy decision and an approval requirement are all ordinary outcomes
 * that the agent has to be able to explain to a buyer, and each arrives here with a stable
 * {@code errorCode} the agent reports rather than reinterprets. The alternative — throwing
 * past the model — produces an agent that says "something went wrong" about a decline whose
 * reason the platform stated precisely.
 *
 * <p>{@code payload} is always a record defined in this service — {@code ProductView},
 * {@code CheckoutView} and the like. It is never a provider response object, never a raw JSON
 * tree, and never anything a downstream system authored: what goes into the model's context
 * is a shape this service chose, containing fields this service decided the model should see.
 *
 * <p>{@code errorMessage} passes through {@link Redactor} on the way in. Failure text can
 * originate at a provider, and a provider's error message is written by someone else and could
 * contain anything — including, in the worst case, a credential echoed back from a request.
 */
public record ToolResult(String toolName, boolean ok, Object payload, String errorCode, String errorMessage) {

    public ToolResult {
        Objects.requireNonNull(toolName, "toolName");
    }

    public static ToolResult ok(String toolName, Object payload) {
        return new ToolResult(toolName, true, payload, null, null);
    }

    /**
     * A failure the agent should explain using the code, not paraphrase.
     *
     * @param errorCode a stable code from this service or from the platform, never free text
     */
    public static ToolResult failure(String toolName, String errorCode, String errorMessage) {
        return new ToolResult(toolName, false, null, errorCode, Redactor.redactText(errorMessage));
    }

    /**
     * A failure that also carries structured detail — a declined payment, for instance, where
     * the payment record itself is what the agent needs in order to explain the decline.
     */
    public static ToolResult failure(String toolName, String errorCode, String errorMessage, Object payload) {
        return new ToolResult(toolName, false, payload, errorCode, Redactor.redactText(errorMessage));
    }
}
