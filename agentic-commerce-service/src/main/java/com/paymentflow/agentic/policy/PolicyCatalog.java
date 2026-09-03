package com.paymentflow.agentic.policy;

import com.paymentflow.agentic.config.AgenticProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * A read projection of the policy the engine actually enforces (G-3).
 *
 * <p><b>Nothing here is hand-transcribed.</b> The rows are {@link PolicyRule#values()} — the same
 * enum {@link PolicyEngine} branches on — and every threshold is read live from
 * {@link AgenticProperties.Policy}, the same record the engine reads. There is no second copy of
 * the numbers to drift: if the config changes, this changes with it, and if a rule is added to
 * the enum it appears here without anyone editing this file. That is the whole point — a portal
 * that showed a policy number the runtime did not use would be worse than showing none.
 *
 * <p>The {@code decision} on each row is {@link PolicyRule#decision()} verbatim: exactly one
 * outcome per rule, so a reader knows both which rule fires and what it does. Only
 * {@link PolicyRule#REFUND_APPROVAL_THRESHOLD} is waivable — everything above it is an outer
 * bound no human may sign off, and the ordering that guarantees that is a property of
 * {@link PolicyEngine}, restated in {@link #describe()}'s comment.
 */
@Component
public class PolicyCatalog {

    private final AgenticProperties properties;

    public PolicyCatalog(AgenticProperties properties) {
        this.properties = properties;
    }

    /** Which group a rule belongs to, for the reader — matches the sections in {@link PolicyRule}. */
    public enum Phase {
        STRUCTURAL_PRECONDITION,
        MONEY_PRECONDITION,
        CAP,
        BUDGET,
        APPROVAL,
        DEFAULT
    }

    /** The unit a threshold is expressed in. */
    public enum ThresholdUnit {
        /** Money, in the currency's minor unit. */
        MINOR,
        /** A plain count (tool calls). */
        COUNT,
        /** No threshold — the rule is structural. */
        NONE
    }

    /**
     * One rule, as the portal shows it.
     *
     * @param id            the stable id written to {@code policy_decisions.rule_id}
     * @param phase         which evaluation group it is in
     * @param decision      the one outcome this rule produces
     * @param reasonCode    the stable machine-readable reason it emits
     * @param scope         "per-payment", "per-conversation", or {@code null}
     * @param thresholdUnit MINOR / COUNT / NONE
     * @param threshold     the configured number, or {@code null} for a structural rule. When a
     *                      cap is non-positive the engine treats the operation as disabled, and
     *                      {@code disabled} reflects that
     * @param disabled      true when a non-positive threshold means "this operation cannot run"
     * @param waivable      whether a human approval can permit an action this rule stopped —
     *                      true only for the refund approval threshold
     * @param description   a one-line explanation, for the reader
     */
    public record PolicyRuleView(
            String id,
            Phase phase,
            PolicyDecision decision,
            String reasonCode,
            String scope,
            ThresholdUnit thresholdUnit,
            Long threshold,
            boolean disabled,
            boolean waivable,
            String description) {
    }

    public String policyVersion() {
        return properties.policy().version();
    }

    public String currency() {
        return properties.policy().currency();
    }

    /**
     * Every rule, in declaration order (which is <em>not</em> the evaluation order — see
     * {@link PolicyEngine} for that, and for why the ordering between a cap and the approval
     * threshold is a security property).
     */
    public List<PolicyRuleView> describe() {
        AgenticProperties.Policy p = properties.policy();
        return List.of(
                structural(PolicyRule.MODE_CONFINED,
                        "The extension operates in test mode only; a live-mode action is refused."),
                structural(PolicyRule.ACTOR_REQUIRED,
                        "An action with no identifiable actor cannot be attributed, so it is refused."),
                structural(PolicyRule.TOOL_ALLOW_LIST,
                        "Defence in depth behind the tool registry: an unregistered tool cannot reach a money call."),
                structural(PolicyRule.CONVERSATION_ACTIVE,
                        "A closed conversation can take no further action — every tool, not only money ones."),
                count(PolicyRule.TOOL_CALL_CEILING, "per-conversation", p.maxToolCallsPerConversation(),
                        "A runaway agent exhausts this ceiling of tool calls instead of the merchant's rate limit."),

                structural(PolicyRule.AMOUNT_RESOLVED,
                        "A money action must carry a server-derived amount; one without has skipped resolution and is refused."),
                structural(PolicyRule.CURRENCY_PERMITTED,
                        "This policy permits one currency (" + p.currency() + "); anything else is refused."),
                structural(PolicyRule.PAYMENT_TARGET_REQUIRED,
                        "A payment must name the checkout whose total it is paying."),
                structural(PolicyRule.REFUND_TARGET_REQUIRED,
                        "A refund must name the payment it is refunding."),
                structural(PolicyRule.CHECKOUT_PAYABLE,
                        "A payment may only be attempted from an OPEN or LOCKED checkout."),

                money(PolicyRule.PAYMENT_AMOUNT_CAP, Phase.CAP, "per-payment", p.maxPaymentAmountMinor(),
                        "A single payment above this is refused outright, whatever the conversation said."),
                money(PolicyRule.CONVERSATION_SPEND_BUDGET, Phase.BUDGET, "per-conversation",
                        p.maxConversationSpendMinor(),
                        "Cumulative payment spend across one conversation may not exceed this."),
                money(PolicyRule.REFUND_AMOUNT_CAP, Phase.CAP, "per-refund", p.maxRefundAmountMinor(),
                        "A single refund above this is refused outright — no approval can permit it."),
                money(PolicyRule.CONVERSATION_REFUND_BUDGET, Phase.BUDGET, "per-conversation",
                        p.maxConversationRefundMinor(),
                        "Cumulative refunds across one conversation may not exceed this."),

                approval(PolicyRule.REFUND_APPROVAL_THRESHOLD, p.refundApprovalThresholdMinor(),
                        "A refund above this is within the outer bounds but needs a human; at or below it executes on policy alone."),

                new PolicyRuleView(PolicyRule.DEFAULT_PERMIT.id(), Phase.DEFAULT,
                        PolicyRule.DEFAULT_PERMIT.decision(), PolicyRule.DEFAULT_PERMIT.reasonCode(),
                        null, ThresholdUnit.NONE, null, false, false,
                        "Nothing objected. Recorded so a permitted action leaves the same shape of row a refused one does."));
    }

    private static PolicyRuleView structural(PolicyRule rule, String description) {
        return new PolicyRuleView(rule.id(), Phase.STRUCTURAL_PRECONDITION, rule.decision(),
                rule.reasonCode(), null, ThresholdUnit.NONE, null, false, false, description);
    }

    private static PolicyRuleView count(PolicyRule rule, String scope, int threshold, String description) {
        return new PolicyRuleView(rule.id(), Phase.STRUCTURAL_PRECONDITION, rule.decision(),
                rule.reasonCode(), scope, ThresholdUnit.COUNT, (long) threshold, threshold <= 0, false,
                description);
    }

    private static PolicyRuleView money(PolicyRule rule, Phase phase, String scope, long threshold,
                                        String description) {
        return new PolicyRuleView(rule.id(), phase, rule.decision(), rule.reasonCode(), scope,
                ThresholdUnit.MINOR, threshold, threshold <= 0, false, description);
    }

    private static PolicyRuleView approval(PolicyRule rule, long threshold, String description) {
        return new PolicyRuleView(rule.id(), Phase.APPROVAL, rule.decision(), rule.reasonCode(),
                "per-refund", ThresholdUnit.MINOR, threshold, threshold <= 0, true, description);
    }
}
