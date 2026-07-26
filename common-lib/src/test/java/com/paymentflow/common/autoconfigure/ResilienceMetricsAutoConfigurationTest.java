package com.paymentflow.common.autoconfigure;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M20.7 — closes V1 known issue #9.
 *
 * <p>These assertions are deliberately about <b>meters existing in the registry</b>, not about
 * beans being created. The issue V1 recorded and M14 re-confirmed was precisely that the
 * dependency and its configuration were present while the meters were not, so a test that
 * checked wiring rather than output would have passed throughout the two years the meters were
 * missing.
 */
class ResilienceMetricsAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ResilienceMetricsAutoConfiguration.class));

    @Configuration(proxyBeanMethods = false)
    static class RegistriesConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        CircuitBreakerRegistry circuitBreakerRegistry() {
            CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
            registry.circuitBreaker("merchantService");
            return registry;
        }

        @Bean
        RetryRegistry retryRegistry() {
            RetryRegistry registry = RetryRegistry.ofDefaults();
            registry.retry("merchantService");
            return registry;
        }

        @Bean
        TimeLimiterRegistry timeLimiterRegistry() {
            TimeLimiterRegistry registry = TimeLimiterRegistry.ofDefaults();
            registry.timeLimiter("merchantService");
            return registry;
        }
    }

    /** Only a MeterRegistry — the case a service with no Resilience4j usage presents. */
    @Configuration(proxyBeanMethods = false)
    static class MeterRegistryOnlyConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Test
    @DisplayName("circuit-breaker meters are actually present in the registry")
    void registersCircuitBreakerMeters() {
        runner.withUserConfiguration(RegistriesConfig.class).run(context -> {
            MeterRegistry meterRegistry = context.getBean(MeterRegistry.class);
            assertThat(meterRegistry.getMeters())
                    .extracting(meter -> meter.getId().getName())
                    .anyMatch(name -> name.startsWith("resilience4j.circuitbreaker"));
            // The specific meter an operator looks at first during an incident.
            assertThat(meterRegistry.find("resilience4j.circuitbreaker.state")
                    .tag("name", "merchantService").gauges()).isNotEmpty();
        });
    }

    @Test
    @DisplayName("retry and time-limiter meters are present too")
    void registersTheOtherRegistries() {
        runner.withUserConfiguration(RegistriesConfig.class).run(context -> {
            MeterRegistry meterRegistry = context.getBean(MeterRegistry.class);
            assertThat(meterRegistry.getMeters())
                    .extracting(meter -> meter.getId().getName())
                    .anyMatch(name -> name.startsWith("resilience4j.retry"))
                    .anyMatch(name -> name.startsWith("resilience4j.timelimiter"));
            assertThat(context.getBean(ResilienceMetricsAutoConfiguration.ResilienceMetricsBinder.class)
                    .boundRegistryCount()).isEqualTo(3);
        });
    }

    @Test
    @DisplayName("a circuit breaker created after startup is still metered")
    void metersInstancesCreatedLater() {
        // bindTo is prospective as well as retroactive; without that, an instance created
        // lazily on first use — which is how Resilience4j normally creates them — would never
        // appear, and the meters would be missing for exactly the breakers under load.
        runner.withUserConfiguration(RegistriesConfig.class).run(context -> {
            CircuitBreakerRegistry circuitBreakers = context.getBean(CircuitBreakerRegistry.class);
            circuitBreakers.circuitBreaker("sandboxService");

            assertThat(context.getBean(MeterRegistry.class)
                    .find("resilience4j.circuitbreaker.state").tag("name", "sandboxService").gauges())
                    .isNotEmpty();
        });
    }

    @Test
    @DisplayName("a service with a meter registry but no Resilience4j registries starts cleanly")
    void toleratesMissingRegistries() {
        runner.withUserConfiguration(MeterRegistryOnlyConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ResilienceMetricsAutoConfiguration.ResilienceMetricsBinder.class)
                    .boundRegistryCount()).isZero();
        });
    }

    @Test
    @DisplayName("the binding can be switched off")
    void canBeDisabled() {
        runner.withUserConfiguration(RegistriesConfig.class)
                .withPropertyValues("paymentflow.resilience-metrics.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(ResilienceMetricsAutoConfiguration.ResilienceMetricsBinder.class));
    }
}
