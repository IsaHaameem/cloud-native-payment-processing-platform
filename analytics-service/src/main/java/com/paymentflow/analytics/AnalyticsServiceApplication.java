package com.paymentflow.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Analytics Service — per-merchant/currency payment read-models, the
 * hourly payment series (M19.6), and the API request log with its usage aggregates (M20).
 *
 * <p>{@code @EnableScheduling} arrives in M20.3 for {@code RequestLogPartitionManager}, and
 * is used again by M20.4's rollup and pruner. Same shape as every other scheduled component
 * on this platform (the outbox relays, {@code WebhookRetryRelay}, {@code ScheduledOutcomeRelay}).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableKafka
@EnableScheduling
public class AnalyticsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnalyticsServiceApplication.class, args);
    }
}
