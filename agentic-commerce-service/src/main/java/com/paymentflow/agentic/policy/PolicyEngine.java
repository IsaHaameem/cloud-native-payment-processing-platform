package com.paymentflow.agentic.policy;

import com.paymentflow.agentic.checkout.CheckoutStatus;
import com.paymentflow.agentic.config.AgenticProperties;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * The deterministic gate every agent action passes before anything financial happens.
 *
 * <p><b>The model is not in this class and cannot reach it.</b> {@link PolicyRequest} carries
 * no field a model can write, this engine reads nothing but that request and the configured
 * thresholds, and it holds no clock, no randomness, no database and no network. Two
 * evaluations of equal requests under one configuration return equal verdicts, always — which
 * is what makes a persisted decision evidence rather than a note about what someone
 * remembered deciding.
 *
 * <h2>Evaluation order, and why it is a security property</h2>
 *
 * Rules are first-match-wins in the order below. Two orderings in it are load-bearing:
 *
 * <ol>
 *   <li><b>Structural preconditions before anything else.</b> Mode, actor, tool recognition,
 *       conversation liveness and the tool-call ceiling are checked before any amount is
 *       looked at, so a closed conversation or an unrecognised tool cannot reach a cap check
 *       and be permitted by it.</li>
 *   <li><b>Hard caps before the approval threshold.</b> A refund beyond
 *       {@code max-refund-amount-minor} is refused and never offered to a human. Reversing
 *       these two would turn every outer bound into something a tired approver could click
 *       through, which is exactly the failure the outer bound exists to prevent.</li>
 * </ol>
 *
 * <h2>Fail-closed thresholds</h2>
 *
 * A non-positive cap or budget means the operation is <em>disabled</em>, not unlimited. An
 * operator who blanks a threshold gets a service that refuses to move money rather than one
 * that moves any amount, so a misread configuration file fails in the direction that costs
 * nothing.
 *
 * @see PolicyRule for the rule catalogue and the id each decision is recorded under
 */
@Component
public class PolicyEngine {

    /**
     * The checkout states a payment may be attempted from. {@code LOCKED} is included because
     * the payment path locks the quote before evaluating policy — the lock is what freezes the
     * amount being evaluated, so requiring {@code OPEN} here would refuse every real payment.
     */
    private static final Set<CheckoutStatus> PAYABLE_STATES = Set.of(CheckoutStatus.OPEN, CheckoutStatus.LOCKED);

    /** This extension is test-mode only, in the schema's mode constraints and again here. */
    private static final String PERMITTED_MODE = "test";

    private final AgenticProperties properties;

    public PolicyEngine(AgenticProperties properties) {
        this.properties = properties;
    }

    /**
     * Decides whether an action may execute, must not, or needs a human.
     *
     * <p>Pure: no field is read that is not on {@code request} or in configuration, and
     * nothing is written anywhere. Persisting the verdict is {@link PolicyDecisionLog}'s job,
     * and it happens <em>before</em> execution.
     */
    public PolicyVerdict evaluate(PolicyRequest request) {
        AgenticProperties.Policy policy = properties.policy();
        String version = policy.version();

        // ── Structural preconditions ────────────────────────────────────────────────────
        if (!PERMITTED_MODE.equalsIgnoreCase(request.actor().mode())) {
            return refuse(PolicyRule.MODE_CONFINED, version, null,
                    "This service operates in test mode only; the action was requested in mode '%s'."
                            .formatted(request.actor().mode()));
        }
        if (!request.actor().isIdentified()) {
            return refuse(PolicyRule.ACTOR_REQUIRED, version, null,
                    "The action carries no identifiable actor, so it cannot be attributed or permitted.");
        }
        if (request.operation() == null) {
            return refuse(PolicyRule.TOOL_ALLOW_LIST, version, null,
                    "Tool '%s' is not on the allow-list of registered tools.".formatted(request.toolName()));
        }
        if (!request.conversation().active()) {
            return refuse(PolicyRule.CONVERSATION_ACTIVE, version, null,
                    "This conversation is closed and can take no further action.");
        }
        // Strictly greater, not greater-or-equal. The counter is incremented when a call is
        // attempted, so it already includes this one: comparing with >= would refuse the last
        // permitted call and make a ceiling of 60 mean 59.
        int ceiling = policy.maxToolCallsPerConversation();
        if (ceiling <= 0 || request.conversation().toolCallCount() > ceiling) {
            return refuse(PolicyRule.TOOL_CALL_CEILING, version, null,
                    "This conversation has used %d of its %d permitted tool calls."
                            .formatted(request.conversation().toolCallCount(), Math.max(ceiling, 0)));
        }

        // ── Category-specific rules ─────────────────────────────────────────────────────
        return switch (request.operation().category()) {
            case READ, COMMERCE -> permit(version, null,
                    "No financial bound applies to a %s tool."
                            .formatted(request.operation().category().name().toLowerCase(Locale.ROOT)));
            case PAYMENT -> evaluatePayment(request, policy);
            case REFUND -> evaluateRefund(request, policy);
        };
    }

    // ── Payment ─────────────────────────────────────────────────────────────────────────

    private PolicyVerdict evaluatePayment(PolicyRequest request, AgenticProperties.Policy policy) {
        String version = policy.version();
        PolicyRequest.Target target = request.target();

        // Computed up front so that a refusal records the headroom that existed when it was
        // refused, not merely the fact of the refusal.
        Long remaining = headroom(policy.maxConversationSpendMinor(), request.conversation().spentMinor());

        if (target.checkoutId() == null) {
            return refuse(PolicyRule.PAYMENT_TARGET_REQUIRED, version, remaining,
                    "A payment must name the checkout whose total it is paying.");
        }
        if (!target.hasResolvedAmount()) {
            return refuse(PolicyRule.AMOUNT_RESOLVED, version, remaining,
                    "No server-derived amount was resolved for checkout %s.".formatted(target.checkoutId()));
        }
        if (!isPermittedCurrency(target.currency(), policy)) {
            return refuse(PolicyRule.CURRENCY_PERMITTED, version, remaining,
                    "Currency '%s' is not permitted; this policy permits %s only."
                            .formatted(target.currency(), policy.currency()));
        }
        if (target.checkoutStatus() == null || !PAYABLE_STATES.contains(target.checkoutStatus())) {
            return refuse(PolicyRule.CHECKOUT_PAYABLE, version, remaining,
                    "Checkout %s is in state %s and cannot be paid."
                            .formatted(target.checkoutId(), target.checkoutStatus()));
        }

        long amount = target.amountMinor();
        long cap = policy.maxPaymentAmountMinor();
        if (cap <= 0 || amount > cap) {
            return refuse(PolicyRule.PAYMENT_AMOUNT_CAP, version, remaining,
                    "Amount %d exceeds the per-payment cap of %d (%s, minor units)."
                            .formatted(amount, Math.max(cap, 0), policy.currency()));
        }
        if (exceedsBudget(amount, policy.maxConversationSpendMinor(), request.conversation().spentMinor())) {
            return refuse(PolicyRule.CONVERSATION_SPEND_BUDGET, version, remaining,
                    "Amount %d exceeds the %d remaining of this conversation's %d spend budget (%s, minor units)."
                            .formatted(amount, remaining, Math.max(policy.maxConversationSpendMinor(), 0),
                                    policy.currency()));
        }
        return permit(version, remaining,
                "Amount %d is within the per-payment cap of %d and the %d remaining conversation spend budget."
                        .formatted(amount, cap, remaining));
    }

    // ── Refund ──────────────────────────────────────────────────────────────────────────

    private PolicyVerdict evaluateRefund(PolicyRequest request, AgenticProperties.Policy policy) {
        String version = policy.version();
        PolicyRequest.Target target = request.target();
        Long remaining = headroom(policy.maxConversationRefundMinor(), request.conversation().refundedMinor());

        if (target.paymentId() == null) {
            return refuse(PolicyRule.REFUND_TARGET_REQUIRED, version, remaining,
                    "A refund must name the payment it is refunding.");
        }
        if (!target.hasResolvedAmount()) {
            return refuse(PolicyRule.AMOUNT_RESOLVED, version, remaining,
                    "No server-derived amount was resolved for payment %s.".formatted(target.paymentId()));
        }
        if (!isPermittedCurrency(target.currency(), policy)) {
            return refuse(PolicyRule.CURRENCY_PERMITTED, version, remaining,
                    "Currency '%s' is not permitted; this policy permits %s only."
                            .formatted(target.currency(), policy.currency()));
        }

        long amount = target.amountMinor();
        long cap = policy.maxRefundAmountMinor();
        if (cap <= 0 || amount > cap) {
            return refuse(PolicyRule.REFUND_AMOUNT_CAP, version, remaining,
                    "Amount %d exceeds the per-refund cap of %d (%s, minor units). No approval can permit this."
                            .formatted(amount, Math.max(cap, 0), policy.currency()));
        }
        if (exceedsBudget(amount, policy.maxConversationRefundMinor(), request.conversation().refundedMinor())) {
            return refuse(PolicyRule.CONVERSATION_REFUND_BUDGET, version, remaining,
                    "Amount %d exceeds the %d remaining of this conversation's %d refund budget (%s, minor units)."
                            .formatted(amount, remaining, Math.max(policy.maxConversationRefundMinor(), 0),
                                    policy.currency()));
        }
        // Last, and deliberately so — everything above is an outer bound no human may waive.
        if (amount > policy.refundApprovalThresholdMinor()) {
            return PolicyVerdict.of(PolicyRule.REFUND_APPROVAL_THRESHOLD,
                    "Amount %d is above the %d approval threshold and requires human approval before it executes."
                            .formatted(amount, Math.max(policy.refundApprovalThresholdMinor(), 0)),
                    version, remaining);
        }
        return permit(version, remaining,
                "Amount %d is at or below the %d approval threshold and within the %d remaining refund budget."
                        .formatted(amount, policy.refundApprovalThresholdMinor(), remaining));
    }

    // ── Arithmetic ──────────────────────────────────────────────────────────────────────

    /**
     * Headroom left under a budget, never negative.
     *
     * <p>Expressed as a subtraction rather than by adding the amount to the running total,
     * which is what keeps it overflow-free: both operands are non-negative and bounded by
     * their own configuration, so no intermediate can wrap.
     */
    private static long headroom(long budget, long used) {
        return Math.max(0, Math.max(budget, 0) - used);
    }

    private static boolean exceedsBudget(long amount, long budget, long used) {
        return budget <= 0 || amount > Math.max(budget, 0) - used;
    }

    private static boolean isPermittedCurrency(String currency, AgenticProperties.Policy policy) {
        return currency != null && currency.equalsIgnoreCase(policy.currency());
    }

    private static PolicyVerdict permit(String version, Long remaining, String reason) {
        return PolicyVerdict.of(PolicyRule.DEFAULT_PERMIT, reason, version, remaining);
    }

    private static PolicyVerdict refuse(PolicyRule rule, String version, Long remaining, String reason) {
        return PolicyVerdict.of(rule, reason, version, remaining);
    }
}
