package com.paymentflow.gateway.security.apikey;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The gateway's own local copy of merchant-service's {@code /internal/v1/api-keys/verify}
 * response shape (D36's schema-per-service-messaging convention, applied here to an
 * internal REST contract rather than a Kafka payload). {@code mode} is normalized to
 * lowercase ({@code "test"}/{@code "live"}) on arrival — merchant-service serializes its
 * {@code KeyMode} enum as {@code "TEST"}/{@code "LIVE"}.
 */
public record ApiKeyVerifyResult(
        UUID merchantId,
        UUID keyId,
        String mode,
        List<String> scopes,
        String contactEmail,
        String webhookUrl,
        /*
         * M20.5 / D145. Null means "use the platform default for this mode" — these are
         * overrides, not settings, so an absent value is the normal case rather than an error.
         *
         * Nullability also makes the cache change backwards-compatible: an `apikey:v1:` entry
         * written before this milestone deserializes with all three null and simply resolves to
         * the defaults, so no cache flush is needed on deploy and no request fails while old
         * entries age out.
         */
        Integer rateLimitPerSecond,
        Integer rateLimitBurst,
        Integer dailyQuota,
        /*
         * M21.5: the merchant's pinned API revision, or null if they have not called the public
         * API yet. Same nullability reasoning as the three overrides above — an entry cached
         * before this milestone deserializes with a null pin and resolves to the current
         * revision, so the cache needs no flush.
         */
        String pinnedApiVersion) {

    public ApiKeyVerifyResult {
        mode = mode == null ? null : mode.toLowerCase(Locale.ROOT);
    }

    /** Pre-M20.5 shape, retained so existing tests and call sites compile unchanged. */
    public ApiKeyVerifyResult(UUID merchantId, UUID keyId, String mode, List<String> scopes,
                              String contactEmail, String webhookUrl) {
        this(merchantId, keyId, mode, scopes, contactEmail, webhookUrl, null, null, null, null);
    }

    /** Pre-M21.5 shape, retained for the same reason. */
    public ApiKeyVerifyResult(UUID merchantId, UUID keyId, String mode, List<String> scopes,
                              String contactEmail, String webhookUrl,
                              Integer rateLimitPerSecond, Integer rateLimitBurst, Integer dailyQuota) {
        this(merchantId, keyId, mode, scopes, contactEmail, webhookUrl,
                rateLimitPerSecond, rateLimitBurst, dailyQuota, null);
    }
}
