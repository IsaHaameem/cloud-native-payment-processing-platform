package com.paymentflow.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * TTLs for the {@code apikey:v1:<sha256>} verification cache (M15, §4.8). Positive
 * and negative entries get independent TTLs — negative caching (task 5) exists purely
 * to stop key enumeration from becoming a merchant-service load amplifier, so it can
 * stay short; positive entries balance latency against how long a revoked key might
 * keep authenticating from cache (mitigated further by explicit eviction on
 * revoke/rotate — see merchant-service's {@code ApiKeyService}).
 */
@ConfigurationProperties(prefix = "paymentflow.gateway.api-key-cache")
public record ApiKeyCacheProperties(Duration positiveTtl, Duration negativeTtl) {
}
