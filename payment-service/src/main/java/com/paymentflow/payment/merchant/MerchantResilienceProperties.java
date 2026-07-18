package com.paymentflow.payment.merchant;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the resilience wrapper around the merchant-service Feign call (M8).
 * The Retry/CircuitBreaker/ThreadPoolBulkhead/TimeLimiter instance thresholds
 * themselves live under {@code resilience4j.*} in {@code application.yaml} (the
 * library's own externalized configuration surface); this record covers the two
 * things that surface aren't designed for: the raw Feign socket timeouts, and the
 * exponential-backoff-with-jitter interval function (only reachable via a
 * {@code RetryConfigCustomizer} bean, not plain YAML properties — see D50).
 */
@ConfigurationProperties(prefix = "paymentflow.resilience.merchant-service")
public record MerchantResilienceProperties(
        long connectTimeoutMs,
        long readTimeoutMs,
        long retryInitialIntervalMs,
        double retryMultiplier,
        double retryRandomizationFactor) {
}
