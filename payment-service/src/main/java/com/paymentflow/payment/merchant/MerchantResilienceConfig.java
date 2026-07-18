package com.paymentflow.payment.merchant;

import io.github.resilience4j.common.retry.configuration.RetryConfigCustomizer;
import io.github.resilience4j.core.IntervalFunction;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Exponential backoff with jitter for the {@code merchantService} retry instance.
 * Plain {@code resilience4j.retry.instances.*} YAML properties only support
 * {@code enableExponentialBackoff} XOR {@code enableRandomizedWait} — not both at
 * once — so combining them (the actually-requested behavior) requires this
 * programmatic {@link RetryConfigCustomizer} extension point instead, still reading
 * its parameters from externalized configuration (D50).
 */
@Configuration
public class MerchantResilienceConfig {

    private static final String INSTANCE_NAME = "merchantService";

    @Bean
    public RetryConfigCustomizer merchantServiceRetryCustomizer(MerchantResilienceProperties properties) {
        return RetryConfigCustomizer.of(INSTANCE_NAME, builder -> builder.intervalFunction(
                IntervalFunction.ofExponentialRandomBackoff(
                        Duration.ofMillis(properties.retryInitialIntervalMs()),
                        properties.retryMultiplier(),
                        properties.retryRandomizationFactor())));
    }

    /**
     * Schedules Retry's backoff delays and TimeLimiter's timeout cancellations — a
     * small, dedicated pool, since it only ever schedules lightweight timer callbacks,
     * never the actual merchant-service call itself (that's {@code ThreadPoolBulkhead}'s
     * job). Declared explicitly rather than relying on resilience4j-spring-boot3's own
     * {@code ContextAwareScheduledThreadPoolAutoConfiguration}, which did not activate
     * in this app's context (found via the M8 integration test suite failing to start
     * at all — see Problems in the M8 changelog).
     */
    @Bean
    public ScheduledExecutorService merchantResilienceScheduledExecutorService() {
        return Executors.newScheduledThreadPool(4);
    }
}
