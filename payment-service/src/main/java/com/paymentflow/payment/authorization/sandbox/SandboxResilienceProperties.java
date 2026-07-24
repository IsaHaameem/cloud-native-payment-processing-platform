package com.paymentflow.payment.authorization.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the resilience wrapper around the sandbox-service Feign call (M17.4),
 * mirroring {@code MerchantResilienceProperties}'s shape exactly (M8): the raw Feign
 * socket timeouts and the exponential-backoff-with-jitter interval function, both not
 * expressible in plain {@code resilience4j.*} YAML (D50).
 *
 * <p>{@code serviceKeyId}/{@code serviceScopes} are the fixed internal-context identity
 * payment-service asserts on its own authority when calling sandbox-service (§7 barrier
 * ①): sandbox-service never receives a client-forwarded context for this call, only one
 * payment-service itself signs — for the merchant/mode it has already resolved via its
 * own JWT or API-key authentication — exactly as the gateway signs one for the caller's
 * request (M15, D100), just asserted by a different, equally trusted party.
 */
@ConfigurationProperties(prefix = "paymentflow.resilience.sandbox-service")
public record SandboxResilienceProperties(
        long connectTimeoutMs,
        long readTimeoutMs,
        long retryInitialIntervalMs,
        double retryMultiplier,
        double retryRandomizationFactor,
        String serviceKeyId,
        String serviceScopes) {
}
