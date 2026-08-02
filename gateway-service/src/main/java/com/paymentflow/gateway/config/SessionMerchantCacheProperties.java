package com.paymentflow.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * TTL for the {@code session:merchant:v1:<userId>} lookup cache (M23.0) — the developer
 * portal's counterpart to {@link ApiKeyCacheProperties}.
 *
 * <p><b>One TTL, not two.</b> The key cache carries a separate negative TTL because an
 * unauthenticated caller can guess key strings all day and each miss would otherwise become
 * a merchant-service call. Nothing analogous exists here: reaching this lookup already
 * requires a valid identity-service JWT, so a miss costs an attacker an account. Caching the
 * negative answer would instead buy a real defect — a user who has just completed onboarding
 * would keep being told they have no merchant until the entry aged out, on the first screen
 * they ever see.
 */
@ConfigurationProperties(prefix = "paymentflow.gateway.session-merchant-cache")
public record SessionMerchantCacheProperties(Duration positiveTtl) {
}
