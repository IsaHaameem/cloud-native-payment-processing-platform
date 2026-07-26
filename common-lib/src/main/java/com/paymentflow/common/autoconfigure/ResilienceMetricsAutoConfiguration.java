package com.paymentflow.common.autoconfigure;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRateLimiterMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedTimeLimiterMetrics;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Binds Resilience4j's Micrometer meters to the registry explicitly (M20.7, §5/M20 task 7).
 *
 * <p><b>This closes V1 known issue #9</b> — "Resilience4j meters are absent from
 * {@code /actuator/prometheus} despite the dependency being present" — which V1 recorded, M14
 * re-confirmed, and two milestones left unexplained.
 *
 * <p><b>The cause is a Spring Boot 4 relocation, not a missing dependency.</b>
 * {@code resilience4j-spring-boot3:2.3.0}'s own metrics auto-configurations order themselves
 * with {@code @AutoConfigureAfter} against Boot <em>3</em> classes:
 *
 * <pre>
 *   org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration
 *   org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration
 * </pre>
 *
 * Boot 4 moved both to {@code org.springframework.boot.micrometer.metrics.autoconfigure}, and
 * {@code spring-boot-actuator-autoconfigure-4.0.2.jar} contains neither of the old names. Spring
 * <em>silently drops</em> an ordering hint that names a class it cannot resolve, so Resilience4j's
 * metrics auto-configuration loses its guarantee of running after the {@code MeterRegistry} exists.
 * Its {@code @ConditionalOnBean(MeterRegistry.class)} then evaluates at whatever point it happens to
 * be reached.
 *
 * <p>That is why the symptom looked arbitrary: <b>gateway-service had the meters and payment-service
 * did not</b>, with identical dependencies and identical {@code registerHealthIndicator: true}
 * configuration. The gateway has few enough auto-configurations to win the race; payment-service, with
 * Kafka, JPA and Feign, loses it. Measured before fixing — 29 {@code resilience4j_*} meter lines on the
 * gateway, 0 on payment-service out of 229 meter families.
 *
 * <p><b>The fix is to stop depending on ordering at all.</b> Each binder below takes
 * {@link MeterRegistry} as a constructor argument, so the dependency graph — not an annotation naming a
 * class that no longer exists — guarantees the registry is present. {@code bindTo} is retroactive and
 * prospective: it registers meters for instances that already exist and subscribes to the registry's
 * event stream for ones created later, so nothing is missed regardless of when this runs.
 *
 * <p>Each registry is optional via {@link ObjectProvider}: a service using only a circuit breaker gets
 * exactly the circuit-breaker meters, and one using no Resilience4j at all never reaches this class
 * because {@link ConditionalOnClass} excludes it.
 */
@AutoConfiguration
@ConditionalOnClass({MeterRegistry.class, CircuitBreakerRegistry.class, TaggedCircuitBreakerMetrics.class})
@ConditionalOnProperty(name = "paymentflow.resilience-metrics.enabled", havingValue = "true",
        matchIfMissing = true)
public class ResilienceMetricsAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ResilienceMetricsAutoConfiguration.class);

    /**
     * Returned as a bean rather than bound in an {@code @PostConstruct} so the binding is a
     * visible, inspectable part of the context rather than a side effect of creating it.
     */
    @Bean
    public ResilienceMetricsBinder paymentflowResilienceMetricsBinder(
            MeterRegistry meterRegistry,
            ObjectProvider<CircuitBreakerRegistry> circuitBreakers,
            ObjectProvider<RetryRegistry> retries,
            ObjectProvider<BulkheadRegistry> bulkheads,
            ObjectProvider<TimeLimiterRegistry> timeLimiters,
            ObjectProvider<RateLimiterRegistry> rateLimiters) {

        int bound = 0;
        CircuitBreakerRegistry circuitBreakerRegistry = circuitBreakers.getIfAvailable();
        if (circuitBreakerRegistry != null) {
            TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakerRegistry).bindTo(meterRegistry);
            bound++;
        }
        RetryRegistry retryRegistry = retries.getIfAvailable();
        if (retryRegistry != null) {
            TaggedRetryMetrics.ofRetryRegistry(retryRegistry).bindTo(meterRegistry);
            bound++;
        }
        BulkheadRegistry bulkheadRegistry = bulkheads.getIfAvailable();
        if (bulkheadRegistry != null) {
            TaggedBulkheadMetrics.ofBulkheadRegistry(bulkheadRegistry).bindTo(meterRegistry);
            bound++;
        }
        TimeLimiterRegistry timeLimiterRegistry = timeLimiters.getIfAvailable();
        if (timeLimiterRegistry != null) {
            TaggedTimeLimiterMetrics.ofTimeLimiterRegistry(timeLimiterRegistry).bindTo(meterRegistry);
            bound++;
        }
        RateLimiterRegistry rateLimiterRegistry = rateLimiters.getIfAvailable();
        if (rateLimiterRegistry != null) {
            TaggedRateLimiterMetrics.ofRateLimiterRegistry(rateLimiterRegistry).bindTo(meterRegistry);
            bound++;
        }

        log.info("Bound {} Resilience4j registries to the meter registry (V1 known issue #9, M20.7)", bound);
        return new ResilienceMetricsBinder(bound);
    }

    /**
     * Marker carrying how many registries were bound. Exists so a test can assert the binding
     * happened rather than inferring it from meter names alone, and so the count is visible in
     * an actuator bean listing during an incident.
     */
    public record ResilienceMetricsBinder(int boundRegistryCount) {
    }
}
