package com.paymentflow.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Notification Service — simulated email + the M18 webhook
 * subsystem.
 *
 * <p>{@code @EnableScheduling} arrives in M18.7 for {@code WebhookRetryRelay}, which
 * polls for deliveries whose scheduled retry has come due. Same shape as
 * payment-service's {@code OutboxRelay} (D3) and sandbox-service's
 * {@code ScheduledOutcomeRelay} (M17.6), and for the same reason: Kafka has no per-message
 * delay, and the schedule spans ~24 hours.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableKafka
@EnableScheduling
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
