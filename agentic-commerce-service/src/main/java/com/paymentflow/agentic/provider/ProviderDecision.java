package com.paymentflow.agentic.provider;

import java.util.Objects;

/**
 * An acquirer's verdict on one authorization, in provider-neutral terms.
 *
 * <p>This is the shape {@code payment-service} eventually sees, and it is deliberately the same
 * vocabulary sandbox-service already speaks — {@code outcome}, {@code declineCode},
 * {@code errorCode}, {@code source}. Nothing Razorpay-specific survives past the adapter that
 * produces this record: not an order id, not a payment id, not an error taxonomy. The payment
 * core does not learn the word Razorpay, and this record is where that stops being an
 * aspiration.
 *
 * @param source            <b>how the verdict was arrived at</b>, and the most important field
 *                          here. {@code payment_collected} means a cardholder actually
 *                          authorized something. {@code order_accepted} means the provider
 *                          accepted an order and <em>nobody authorized anything</em> — see
 *                          {@link #isDemoApproval()}
 * @param providerReference the acquirer's own identifier, carried for reconciliation. Opaque
 * @param demo              whether this verdict represents a real cardholder authorization
 *                          ({@code false}) or a demonstration stand-in for one ({@code true})
 */
public record ProviderDecision(
        ProviderOutcome outcome,
        String declineCode,
        String errorCode,
        String source,
        String providerReference,
        boolean demo) {

    /** A cardholder authorized a payment against the order, and the provider says so. */
    public static final String SOURCE_PAYMENT_COLLECTED = "payment_collected";

    /**
     * The provider accepted an order, and no payment was ever collected against it.
     *
     * <p><b>This source never means a card was authorized.</b> It is the label on the
     * demonstration-only outcome described in {@code RazorpayPaymentProvider}, and its whole
     * purpose is to make the difference legible in an audit trail: a reader who sees this on an
     * approval knows that no cardholder was involved.
     */
    public static final String SOURCE_ORDER_ACCEPTED = "order_accepted";

    /** The provider was asked and could not be reached, or answered with something unusable. */
    public static final String SOURCE_PROVIDER_UNAVAILABLE = "provider_unavailable";

    /** No provider credential is configured, so nothing was asked. */
    public static final String SOURCE_NOT_CONFIGURED = "provider_not_configured";

    public ProviderDecision {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(source, "source");
    }

    /** A real approval: a cardholder authorized this, and the provider reported it. */
    public static ProviderDecision approved(String source, String providerReference) {
        return new ProviderDecision(ProviderOutcome.APPROVE, null, null, source, providerReference, false);
    }

    /**
     * The demonstration approval, and the only thing in this codebase that produces one.
     *
     * <p>Marked {@code demo} and sourced {@link #SOURCE_ORDER_ACCEPTED} so that no reader, log
     * line or audit row can mistake it for a real successful card payment. It is reachable only
     * when an operator has explicitly set {@code razorpay.uncollected-order-outcome=approve},
     * which is not the default.
     */
    public static ProviderDecision demoApproved(String providerReference) {
        return new ProviderDecision(ProviderOutcome.APPROVE, null, null, SOURCE_ORDER_ACCEPTED,
                providerReference, true);
    }

    public static ProviderDecision declined(String declineCode, String source, String providerReference) {
        return new ProviderDecision(ProviderOutcome.DECLINE, declineCode, null, source, providerReference,
                false);
    }

    public static ProviderDecision error(String errorCode, String source) {
        return new ProviderDecision(ProviderOutcome.ERROR, null, errorCode, source, null, false);
    }

    /**
     * Whether this approval is a demonstration stand-in rather than a cardholder authorization.
     *
     * <p>Anything that reports an approval to a human must consult this. An interface that
     * showed a demo approval and a real one identically would undo the entire reason the
     * distinction is carried.
     */
    public boolean isDemoApproval() {
        return demo && outcome == ProviderOutcome.APPROVE;
    }
}
