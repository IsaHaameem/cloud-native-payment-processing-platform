package com.paymentflow.common.security;

import java.util.Set;
import java.util.UUID;

/**
 * The gateway-asserted, HMAC-verified identity of the merchant behind a request (M15) —
 * the equivalent of the JWT subject the platform has used since M2. Carries {@code
 * contactEmail}/{@code webhookUrl} alongside the identity fields (D118) so a service on
 * the API-key path (payment-service's event publisher, D43) never needs a second
 * synchronous lookup merely to learn them.
 *
 * <p>Populated once per request by {@code InternalContextFilter} and read via
 * {@link MerchantContextHolder}; absent entirely on the unmodified JWT path.
 *
 * <p><b>Two principals since M23.0 (D185).</b> An API key identifies itself with {@code
 * keyId} and has no user; a developer-portal session identifies itself with {@code userId}
 * and has no key. Exactly one of the pair is present, and the compact constructor enforces
 * that rather than documenting it — a context claiming to be a session while carrying a key
 * id would be a lie the audit trail then repeats. Prefer {@link #forApiKey} and
 * {@link #forSession} over the canonical constructor: they make the two shapes unmistakable
 * at the call site.
 */
public record MerchantContext(
        UUID merchantId,
        String mode,
        InternalPrincipal principal,
        UUID userId,
        UUID keyId,
        Set<String> scopes,
        String contactEmail,
        String webhookUrl) {

    public MerchantContext {
        if (principal == null) {
            throw new IllegalArgumentException("principal is required");
        }
        if (principal == InternalPrincipal.API_KEY && keyId == null) {
            throw new IllegalArgumentException("An API-key context must carry a keyId");
        }
        if (principal == InternalPrincipal.API_KEY && userId != null) {
            throw new IllegalArgumentException("An API-key context has no user and must not carry a userId");
        }
        if (principal == InternalPrincipal.SESSION && userId == null) {
            throw new IllegalArgumentException("A session context must carry a userId");
        }
        if (principal == InternalPrincipal.SESSION && keyId != null) {
            throw new IllegalArgumentException("A session context has no API key and must not carry a keyId");
        }
        scopes = Set.copyOf(scopes);
    }

    /**
     * Pre-M23.0 shape, retained so every existing call site compiles and keeps meaning
     * exactly what it did — all of them are API-key contexts. Same convention as
     * {@code ApiKeyVerifyResult}'s superseded constructors.
     */
    public MerchantContext(UUID merchantId, String mode, UUID keyId, Set<String> scopes,
                           String contactEmail, String webhookUrl) {
        this(merchantId, mode, InternalPrincipal.API_KEY, null, keyId, scopes, contactEmail, webhookUrl);
    }

    /** A request authenticated by an API key the gateway verified against merchant-service. */
    public static MerchantContext forApiKey(UUID merchantId, String mode, UUID keyId, Set<String> scopes,
                                            String contactEmail, String webhookUrl) {
        return new MerchantContext(merchantId, mode, InternalPrincipal.API_KEY, null, keyId, scopes,
                contactEmail, webhookUrl);
    }

    /** A request authenticated by a developer-portal session, on behalf of {@code userId} (M23.0). */
    public static MerchantContext forSession(UUID merchantId, String mode, UUID userId, Set<String> scopes,
                                             String contactEmail, String webhookUrl) {
        return new MerchantContext(merchantId, mode, InternalPrincipal.SESSION, userId, null, scopes,
                contactEmail, webhookUrl);
    }

    /** {@code "*"} grants every scope, matching the wildcard convention on a key's own scope list. */
    public boolean hasScope(String required) {
        return scopes.contains("*") || scopes.contains(required);
    }
}
