package com.paymentflow.agentic.provider;

/**
 * The three verdicts an acquirer can return, matching the vocabulary sandbox-service already
 * speaks so that {@code payment-service} needs no new concept to consume either of them.
 *
 * <p>There is deliberately no {@code PENDING}. Depth 1 is synchronous: the adapter asks, and it
 * answers with what it can establish right now. An asynchronous settlement would need a webhook,
 * a reconciliation job and a payment state to park in — none of which exists here, and inventing
 * a fourth outcome that nothing can act on would be worse than admitting the scope.
 */
public enum ProviderOutcome {

    /** The acquirer authorized the payment. */
    APPROVE,

    /** The acquirer refused it. {@code declineCode} says why, in the acquirer's own vocabulary. */
    DECLINE,

    /** The attempt failed for a reason that is not a verdict — unreachable, misconfigured, unusable. */
    ERROR
}
