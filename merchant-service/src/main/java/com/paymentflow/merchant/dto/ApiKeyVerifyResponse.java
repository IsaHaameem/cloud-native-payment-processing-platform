package com.paymentflow.merchant.dto;

import com.paymentflow.merchant.domain.KeyMode;

import java.util.List;
import java.util.UUID;

/**
 * The internal, service-to-service verification contract (M15, {@code
 * /internal/v1/api-keys/verify}) — never routed publicly. Carries {@code
 * contactEmail}/{@code webhookUrl} alongside the key identity (D118, approved
 * extension beyond the milestone's original minimal shape) so a caller on the
 * API-key path never needs a second lookup merely to learn them, preserving D43's
 * event-carried delivery info without a feature regression.
 */
public record ApiKeyVerifyResponse(
        UUID merchantId,
        UUID keyId,
        KeyMode mode,
        List<String> scopes,
        String contactEmail,
        String webhookUrl,
        /*
         * M20.5 / D145: the merchant's rate-limit and quota overrides travel on this response
         * because the gateway already resolves and caches it on every API-key request — so
         * enforcing a per-merchant limit costs no extra round trip and no second cache.
         * §4.6's merchant_settings table, which the plan assumed, was never built.
         *
         * Null means "use the platform default for this mode". The gateway, not this service,
         * owns those defaults: merchant-service knows what is exceptional about a merchant,
         * not what is normal for everyone.
         */
        Integer rateLimitPerSecond,
        Integer rateLimitBurst,
        Integer dailyQuota,
        /*
         * M21.5: the merchant's pinned revision of the public contract (§4.10). Rides on this
         * response for the same reason the three overrides above do — the gateway resolves and
         * caches it on every API-key request, so honouring a pin costs no extra round trip.
         *
         * Null means the merchant has not called the public API yet; the gateway pins them on
         * the first request it authenticates. Nullable also keeps the cache change
         * backward-compatible, exactly as D145's did: an `apikey:v1:` entry written before this
         * milestone deserializes with a null pin and resolves to the current revision, so no
         * cache flush is needed on deploy.
         */
        String pinnedApiVersion) {
}
