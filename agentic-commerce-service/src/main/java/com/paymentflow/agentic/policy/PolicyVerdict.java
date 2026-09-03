package com.paymentflow.agentic.policy;

/**
 * What the engine decided, and everything needed to explain or reproduce it.
 *
 * <p>{@code policyVersion} is carried on every verdict rather than looked up at read time.
 * Thresholds change; a decision made under the old ones stays interpretable only if it
 * records which ones it was made under. Without it the audit trail would say what was decided
 * but not what the rules were at the time, which is the half that matters when someone asks
 * why a refund was waved through last month and stopped today.
 *
 * <p>{@code reason} is assembled from server-side numbers alone. No part of it originates in
 * model output, so a verdict can be shown to a buyer, written to a log, and handed back to
 * the model as a tool result without any of those three being an injection surface.
 *
 * @param budgetRemainingMinor what the conversation had left under the relevant budget at the
 *                             moment of decision, or {@code null} for a tool with no budget.
 *                             Recorded because "this action was within budget" is otherwise
 *                             unfalsifiable after the fact.
 */
public record PolicyVerdict(
        PolicyDecision decision,
        PolicyRule rule,
        String reason,
        String policyVersion,
        Long budgetRemainingMinor) {

    static PolicyVerdict of(PolicyRule rule, String reason, String policyVersion, Long budgetRemainingMinor) {
        return new PolicyVerdict(rule.decision(), rule, reason, policyVersion, budgetRemainingMinor);
    }

    /** The stable machine-readable reason, from the rule that fired. */
    public String reasonCode() {
        return rule.reasonCode();
    }

    /** The stable rule identifier, from the rule that fired. */
    public String ruleId() {
        return rule.id();
    }

    public boolean permitsExecution() {
        return decision.permitsExecution();
    }

    public boolean requiresApproval() {
        return decision == PolicyDecision.REQUIRES_APPROVAL;
    }
}
