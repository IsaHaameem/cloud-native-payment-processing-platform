package com.paymentflow.agentic.policy;

/**
 * The risk class of a tool, which is what selects the rule set the policy engine applies.
 *
 * <p>AD-12 divides the seven tools into read, commerce and money. This enum splits money into
 * {@link #PAYMENT} and {@link #REFUND} because the two have entirely separate caps, separate
 * conversation budgets, and only one of them has an approval threshold — a single {@code MONEY}
 * constant would force every rule to re-derive which kind it was looking at.
 *
 * <p>A tool never declares this directly. It declares a {@link PolicyOperation}, and the
 * category is read off that. One axis, one source of truth: a tool cannot end up classified as
 * a read while performing a refund.
 */
public enum ToolCategory {

    /** Catalogue and payment reads. Moves nothing, and its failure mode is a typed error. */
    READ,

    /** Creates or changes a checkout. Decides an amount, but does not send one anywhere. */
    COMMERCE,

    /** Charges the buyer. */
    PAYMENT,

    /** Returns money to the buyer. */
    REFUND;

    public boolean movesMoney() {
        return this == PAYMENT || this == REFUND;
    }
}
