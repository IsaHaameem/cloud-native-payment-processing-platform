package com.paymentflow.payment.authorization.sandbox;

import io.github.resilience4j.common.retry.configuration.RetryConfigCustomizer;
import io.github.resilience4j.core.IntervalFunction;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Exponential backoff with jitter for the {@code sandboxService} retry instance —
 * mirrors {@code MerchantResilienceConfig}'s rationale exactly (D50). Deliberately
 * declares no {@code ScheduledExecutorService} of its own: {@code
 * MerchantResilienceConfig} already provides one shared pool (M8) for scheduling
 * Retry/TimeLimiter callbacks across the application, and that pool's job — lightweight
 * timer callbacks, never the call itself (that's each instance's own
 * {@code ThreadPoolBulkhead}) — is already generic, not merchant-specific.
 */
@Configuration
public class SandboxResilienceConfig {

    private static final String INSTANCE_NAME = "sandboxService";

    @Bean
    public RetryConfigCustomizer sandboxServiceRetryCustomizer(SandboxResilienceProperties properties) {
        return RetryConfigCustomizer.of(INSTANCE_NAME, builder -> builder.intervalFunction(
                IntervalFunction.ofExponentialRandomBackoff(
                        Duration.ofMillis(properties.retryInitialIntervalMs()),
                        properties.retryMultiplier(),
                        properties.retryRandomizationFactor())));
    }
}
