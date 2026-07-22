package com.paymentflow.common.security;

import java.util.Set;
import java.util.UUID;

/**
 * The gateway-asserted, HMAC-verified identity of the merchant behind an API-key
 * request (M15) — the API-key-path equivalent of the JWT subject the platform has
 * used since M2. Carries {@code contactEmail}/{@code webhookUrl} alongside the
 * identity fields (D118) so a service on the API-key path (payment-service's event
 * publisher, D43) never needs a second synchronous lookup merely to learn them.
 *
 * <p>Populated once per request by {@code InternalContextFilter} and read via
 * {@link MerchantContextHolder}; absent entirely on the unmodified JWT path.
 */
public record MerchantContext(
        UUID merchantId,
        String mode,
        UUID keyId,
        Set<String> scopes,
        String contactEmail,
        String webhookUrl) {

    public MerchantContext {
        scopes = Set.copyOf(scopes);
    }

    /** {@code "*"} grants every scope, matching the wildcard convention on a key's own scope list. */
    public boolean hasScope(String required) {
        return scopes.contains("*") || scopes.contains(required);
    }
}
