package com.paymentflow.agentic.approval;

import com.paymentflow.agentic.policy.PolicyOperation;
import com.paymentflow.agentic.policy.PolicyRequest;

import java.util.Objects;
import java.util.UUID;

/**
 * Exactly what an approval authorises — <b>and the only thing it authorises</b>.
 *
 * <p>This record is the answer to the question an approval workflow lives or dies on: what
 * did the human actually agree to? Not "a refund on this conversation", but this operation,
 * for this merchant, in this mode, against this payment or this checkout, for this amount, in
 * this currency. Anything that differs in any of those is a different action, and a different
 * action needs its own approval.
 *
 * <p>The threat this closes is specific and not hypothetical. An agent asks to refund ₹500,
 * a human approves it, and the agent then executes a refund of ₹20,000 against the same
 * approval — or against a different payment, or in a different currency. Every one of those
 * is caught here, by comparing the binding frozen at request time against the binding
 * resolved at execution time, and refusing on the first field that moved.
 *
 * <p>Comparison is exact, with one deliberate exception: {@code currency} is compared
 * case-insensitively, because {@code inr} and {@code INR} are the same currency and treating
 * them as different would be a false alarm rather than a caught attack. Amounts, ids and the
 * operation are compared as equals with no tolerance at all.
 */
public record ApprovalBinding(
        UUID merchantId,
        String mode,
        PolicyOperation operation,
        UUID checkoutId,
        UUID paymentId,
        Long amountMinor,
        String currency) {

    public ApprovalBinding {
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(operation, "operation");
    }

    /**
     * The binding implied by a policy request — the shape frozen when the approval is asked
     * for, and re-derived from server-side facts when it is redeemed.
     *
     * <p>Derived from {@link PolicyRequest} rather than assembled by hand at each call site,
     * so the thing that gets approved and the thing that gets executed are projections of the
     * same source. Two hand-written projections is how a binding check ends up comparing a
     * field against itself.
     */
    public static ApprovalBinding of(PolicyRequest request) {
        PolicyRequest.Target target = request.target();
        return new ApprovalBinding(
                request.actor().merchantId(),
                request.actor().mode(),
                request.operation(),
                target.checkoutId(),
                target.paymentId(),
                target.amountMinor(),
                target.currency());
    }

    /**
     * The name of the first field that differs from {@code other}, or {@code null} if the two
     * describe the same action.
     *
     * <p>Returns the field name rather than a boolean so the refusal can say <em>what</em>
     * moved. "The amount has changed" and "the payment has changed" want different reactions
     * from whoever reads the log, and a bare "binding mismatch" gives them neither.
     *
     * <p>The order of the checks is the order of severity, so a request that changed several
     * things at once is reported by the most alarming one.
     */
    public String firstDifferenceFrom(ApprovalBinding other) {
        if (other == null) {
            return "binding";
        }
        if (!Objects.equals(merchantId, other.merchantId)) {
            return "merchant";
        }
        if (!Objects.equals(mode, other.mode)) {
            return "mode";
        }
        if (operation != other.operation) {
            return "operation";
        }
        if (!Objects.equals(amountMinor, other.amountMinor)) {
            return "amount";
        }
        if (!sameCurrency(currency, other.currency)) {
            return "currency";
        }
        if (!Objects.equals(checkoutId, other.checkoutId)) {
            return "checkout";
        }
        if (!Objects.equals(paymentId, other.paymentId)) {
            return "payment";
        }
        return null;
    }

    public boolean matches(ApprovalBinding other) {
        return firstDifferenceFrom(other) == null;
    }

    private static boolean sameCurrency(String left, String right) {
        return left == null ? right == null : left.equalsIgnoreCase(right);
    }
}
