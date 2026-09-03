package com.paymentflow.agentic.policy;

/**
 * The three things the policy engine can say about a proposed action.
 *
 * <p>There is deliberately no fourth. In particular there is no "permit with a warning" and
 * no "permit but smaller": a decision that quietly changes the action it was asked about
 * would make the persisted record a description of something that never happened, and the
 * whole value of writing the decision down before executing is that the row and the action
 * are the same thing.
 */
public enum PolicyDecision {

    /** The action may execute now, unchanged. */
    PERMIT,

    /**
     * The action must not execute, and no approval can change that. A refusal is terminal by
     * construction — the caps that produce one are the outer bound of what this agent may do
     * at all, not a threshold someone can sign off.
     */
    REFUSE,

    /**
     * The action is within the outer bounds but needs a human. <b>Nothing financial executes
     * until an approval bound to these exact parameters is granted.</b> The approval binds to
     * the amount, currency, checkout, merchant and operation that were evaluated here; a
     * later action differing in any of them is a different action and needs its own approval.
     */
    REQUIRES_APPROVAL;

    /** Whether a financial call may be made on the strength of this decision alone. */
    public boolean permitsExecution() {
        return this == PERMIT;
    }
}
