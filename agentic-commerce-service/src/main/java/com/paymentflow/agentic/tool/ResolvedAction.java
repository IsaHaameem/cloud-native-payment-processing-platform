package com.paymentflow.agentic.tool;

import com.paymentflow.agentic.action.Redactor;
import com.paymentflow.agentic.policy.PolicyRequest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What a validated tool call actually resolves to, once server-side facts have been read.
 *
 * <p><b>This is the step where an amount comes into existence, and it comes from the
 * database.</b> The model named a checkout; {@code resolve} read that checkout's own row and
 * put its own total here. There is no path by which a number in a tool argument becomes the
 * {@code amountMinor} on this record — the money tools do not declare such an argument, and
 * if one arrived anyway {@link ToolArguments#requireOnly} would have rejected the call before
 * this point.
 *
 * <p>Resolution is deliberately separate from execution. Between the two sit the policy
 * engine and, when policy says so, a human — and both of them need the resolved amount in
 * order to decide anything. A tool that resolved and executed in one step would be a tool
 * whose amount was only knowable after it had been charged.
 *
 * @param target      the server-derived facts the policy engine evaluates
 * @param arguments   the validated arguments, for the action log's redacted summary. Never the
 *                    raw model payload
 * @param description a plain statement of what will happen, assembled from server-side facts.
 *                    Shown to an approver and recorded on the approval
 */
public record ResolvedAction(PolicyRequest.Target target, Map<String, Object> arguments, String description) {

    public ResolvedAction {
        Objects.requireNonNull(target, "target");
        arguments = arguments == null ? Map.of() : new LinkedHashMap<>(arguments);
    }

    /** A tool that touches nothing financial: no amount, no target, nothing for policy to bound. */
    public static ResolvedAction nonFinancial(Map<String, Object> arguments, String description) {
        return new ResolvedAction(PolicyRequest.Target.none(), arguments, description);
    }

    /**
     * The canonical, redacted line the action log stores.
     *
     * <p>Produced here rather than at the call site so that every action — read, commerce and
     * money alike — is summarised by the same code, and no tool can opt out of redaction by
     * assembling its own summary.
     */
    public String inputSummary() {
        return Redactor.summarise(arguments);
    }
}
