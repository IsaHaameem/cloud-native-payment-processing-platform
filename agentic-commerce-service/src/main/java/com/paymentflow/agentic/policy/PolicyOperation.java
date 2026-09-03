package com.paymentflow.agentic.policy;

/**
 * The operation a tool will perform, as a closed vocabulary.
 *
 * <p>This is the field an <b>approval binds to</b>, and that is the reason it is an enum
 * rather than the tool's name. A tool name is an implementation label that could be renamed
 * or re-pointed; the operation is what the approver was actually agreeing to. An approval
 * granted for {@link #REFUND_CREATE} can never be spent on a {@link #CHECKOUT_PAY}, whatever
 * the tool registry looks like by the time it is redeemed.
 *
 * <p>Each constant carries its {@link ToolCategory}, so the risk class and the operation can
 * never disagree.
 */
public enum PolicyOperation {

    /** {@code search_products}, {@code get_product}. */
    CATALOG_READ(ToolCategory.READ),

    /** {@code get_payment_status}, {@code explain_payment_outcome}. */
    PAYMENT_READ(ToolCategory.READ),

    /** {@code create_checkout}. Prices server-side from the catalogue. */
    CHECKOUT_CREATE(ToolCategory.COMMERCE),

    /** Adding, removing or re-quantifying a line on an existing checkout. */
    CHECKOUT_MODIFY(ToolCategory.COMMERCE),

    /**
     * {@code complete_checkout}. The composite money path — create, authorize, capture — run
     * deterministically inside the tool layer for the amount the checkout itself derived.
     */
    CHECKOUT_PAY(ToolCategory.PAYMENT),

    /** {@code request_refund}. */
    REFUND_CREATE(ToolCategory.REFUND);

    private final ToolCategory category;

    PolicyOperation(ToolCategory category) {
        this.category = category;
    }

    public ToolCategory category() {
        return category;
    }

    public boolean movesMoney() {
        return category.movesMoney();
    }
}
