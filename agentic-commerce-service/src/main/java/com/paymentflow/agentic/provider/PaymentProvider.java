package com.paymentflow.agentic.provider;

/**
 * The acquirer port. One method, because Depth 1 needs one.
 *
 * <p><b>There is deliberately no {@code capture} and no {@code refund} here.</b> Both would be
 * easy to add and neither is in scope: adding a port method that nothing implements and nothing
 * calls is how a codebase acquires an interface that describes an intention rather than a
 * capability, and the first person to find it will reasonably assume the capability exists.
 * When capture and refund are actually needed against a real provider, they arrive together
 * with the reconciliation they require.
 *
 * <p>Implementations live in this service and nowhere else. {@code payment-service} depends on
 * its own {@code AuthorizationAdvisor} port and reaches this one, if at all, over HTTP through
 * the provider-decision endpoint — so no provider library, credential or vocabulary is ever on
 * the payment core's classpath.
 */
public interface PaymentProvider {

    /** Which provider this is, for logging and for the decision trail. */
    String providerName();

    /** Whether a usable credential is configured. Checked before any outbound call is attempted. */
    boolean isConfigured();

    /**
     * Asks the acquirer to authorize a payment.
     *
     * <p>Never throws for an ordinary failure. An unreachable provider, a missing credential and
     * a refused request all come back as a {@link ProviderDecision} with an outcome the caller
     * can record and report — because the caller is answering {@code payment-service}, and an
     * exception there would become an opaque 500 in place of a decision the platform could act
     * on.
     */
    ProviderDecision authorize(ProviderAuthorizationRequest request);
}
