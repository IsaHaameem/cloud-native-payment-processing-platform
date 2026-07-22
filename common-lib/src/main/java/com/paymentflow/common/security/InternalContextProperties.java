package com.paymentflow.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The shared HMAC secret every service uses to verify a gateway-asserted merchant
 * context (M15, D100), plus how much clock skew between the gateway and this service
 * is tolerated before a signed context is treated as stale. Sourced from an env var
 * (Secrets Manager in AWS, {@code .env} locally — D18/D73's pattern); {@code
 * maxClockSkewSeconds} is deliberately externalized rather than hardcoded so it can be
 * tuned without a redeploy if real clock drift is ever observed between containers.
 */
@ConfigurationProperties(prefix = "paymentflow.internal-context")
public record InternalContextProperties(String secret, long maxClockSkewSeconds) {
}
