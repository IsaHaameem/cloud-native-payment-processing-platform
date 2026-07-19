package com.paymentflow.common.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Tags every metric emitted by a service with {@code application=<spring.application.name>}
 * (M13) — the one common tag every Prometheus query/Grafana dashboard needs to distinguish
 * one service's series from another's once all 9 services are scraped into the same
 * Prometheus instance. Declared once here instead of repeating
 * {@code management.metrics.tags.application} in 9 near-identical {@code application.yaml}
 * blocks (this project's standing "no duplicated code" requirement, same reasoning as
 * {@link CorrelationIdAutoConfiguration}).
 */
@AutoConfiguration
@ConditionalOnClass(MeterRegistry.class)
public class ObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "commonTagsMeterRegistryCustomizer")
    public MeterRegistryCustomizer<MeterRegistry> commonTagsMeterRegistryCustomizer(
            @Value("${spring.application.name}") String applicationName) {
        return registry -> registry.config()
                .meterFilter(MeterFilter.commonTags(List.of(Tag.of("application", applicationName))));
    }
}
