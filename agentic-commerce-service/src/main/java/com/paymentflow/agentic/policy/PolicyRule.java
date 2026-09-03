package com.paymentflow.agentic.policy;

/**
 * Every rule the engine can apply, each with the stable id it is recorded under, the reason
 * code it emits, and the one decision it produces.
 *
 * <p>Two properties are worth stating because both are load-bearing.
 *
 * <p><b>A rule has exactly one outcome.</b> {@link #REFUND_APPROVAL_THRESHOLD} always means
 * {@code REQUIRES_APPROVAL}; {@link #PAYMENT_AMOUNT_CAP} always means {@code REFUSE}. A rule
 * that could return either would make the persisted {@code rule_id} ambiguous — a reader
 * would know which rule fired but not what it did — and it is precisely the combination of
 * the two that a decision has to be reproducible from.
 *
 * <p><b>The ids are stable strings, not enum names.</b> They are written to
 * {@code policy_decisions.rule_id} and read back long after the code has moved on. Renaming a
 * constant must not silently reinterpret rows already on disk, so the id is declared here and
 * never derived from {@link #name()}.
 *
 * <p>The declaration order is not the evaluation order. {@link PolicyEngine} owns that, and
 * says so explicitly, because the ordering between a hard cap and an approval threshold is a
 * security property rather than a stylistic one.
 */
public enum PolicyRule {

    // ── Structural preconditions, applied to every tool ──────────────────────────────────

    /** This extension is test-mode only, in the schema and here. */
    MODE_CONFINED("mode-confined", "mode_not_permitted", PolicyDecision.REFUSE),

    /** An action with no identifiable actor cannot be attributed, so it cannot be permitted. */
    ACTOR_REQUIRED("actor-required", "actor_missing", PolicyDecision.REFUSE),

    /**
     * Defence in depth behind the tool registry. The registry rejects an unregistered tool
     * before policy is ever consulted; this rule means a pipeline that somehow skipped that
     * check still cannot reach a money call.
     */
    TOOL_ALLOW_LIST("tool-allow-list", "tool_not_allow_listed", PolicyDecision.REFUSE),

    CONVERSATION_ACTIVE("conversation-active", "conversation_closed", PolicyDecision.REFUSE),

    /** The ceiling a runaway model exhausts instead of the merchant's rate limit. */
    TOOL_CALL_CEILING("tool-call-ceiling", "tool_budget_exhausted", PolicyDecision.REFUSE),

    // ── Money preconditions ─────────────────────────────────────────────────────────────

    /**
     * The amount must already have been resolved from server-side facts. A money action
     * arriving here without one has skipped the resolution step, and the only safe reading of
     * that is that the amount would have come from the model.
     */
    AMOUNT_RESOLVED("amount-resolved", "amount_not_resolved", PolicyDecision.REFUSE),

    CURRENCY_PERMITTED("currency-permitted", "currency_not_permitted", PolicyDecision.REFUSE),

    /** A payment must name the checkout whose total it is paying. */
    PAYMENT_TARGET_REQUIRED("payment-target-required", "checkout_missing", PolicyDecision.REFUSE),

    /** A refund must name the payment it is refunding. */
    REFUND_TARGET_REQUIRED("refund-target-required", "payment_missing", PolicyDecision.REFUSE),

    CHECKOUT_PAYABLE("checkout-payable", "checkout_not_payable", PolicyDecision.REFUSE),

    // ── Caps and budgets ────────────────────────────────────────────────────────────────

    PAYMENT_AMOUNT_CAP("payment-amount-cap", "payment_amount_exceeds_cap", PolicyDecision.REFUSE),

    CONVERSATION_SPEND_BUDGET("conversation-spend-budget", "conversation_spend_budget_exhausted",
            PolicyDecision.REFUSE),

    REFUND_AMOUNT_CAP("refund-amount-cap", "refund_amount_exceeds_cap", PolicyDecision.REFUSE),

    CONVERSATION_REFUND_BUDGET("conversation-refund-budget", "conversation_refund_budget_exhausted",
            PolicyDecision.REFUSE),

    // ── Approval ────────────────────────────────────────────────────────────────────────

    /**
     * The only rule in this enum that produces {@code REQUIRES_APPROVAL}, and it is evaluated
     * <em>after</em> both refund caps. That ordering is the point: an amount beyond the hard
     * cap is refused outright rather than offered to a human to wave through.
     */
    REFUND_APPROVAL_THRESHOLD("refund-approval-threshold", "refund_above_approval_threshold",
            PolicyDecision.REQUIRES_APPROVAL),

    // ── Default ─────────────────────────────────────────────────────────────────────────

    /**
     * Nothing objected. Recorded as a rule of its own so that a permitted action leaves the
     * same shape of row a refused one does — an audit trail with entries only for refusals
     * cannot distinguish "allowed" from "never evaluated".
     */
    DEFAULT_PERMIT("default-permit", "permitted", PolicyDecision.PERMIT);

    private final String id;
    private final String reasonCode;
    private final PolicyDecision decision;

    PolicyRule(String id, String reasonCode, PolicyDecision decision) {
        this.id = id;
        this.reasonCode = reasonCode;
        this.decision = decision;
    }

    /** The stable identifier persisted to {@code policy_decisions.rule_id}. */
    public String id() {
        return id;
    }

    /** The stable machine-readable reason, persisted to {@code policy_decisions.reason_code}. */
    public String reasonCode() {
        return reasonCode;
    }

    public PolicyDecision decision() {
        return decision;
    }
}
