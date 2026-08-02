package com.paymentflow.gateway.security.session;

/**
 * The session authenticated, but its user owns no merchant yet (M23.0) — the developer
 * portal's pre-onboarding state.
 *
 * <p>Deliberately distinct from an authentication failure, and deliberately not retried:
 * merchant-service answering "no such merchant" is a correct, fast, final answer, so it is
 * listed in the {@code sessionMerchantLookup} resilience instance's ignored exceptions for
 * the same reason {@code InvalidApiKeyException} is in {@code apiKeyVerify}'s (D51) —
 * a client-side outcome is not a merchant-service health signal.
 */
public class MerchantNotOnboardedException extends RuntimeException {

    public MerchantNotOnboardedException() {
        super("The authenticated user is not associated with a merchant.");
    }
}
