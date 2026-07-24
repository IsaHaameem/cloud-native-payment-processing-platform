package com.paymentflow.sandbox.config;

import com.paymentflow.sandbox.scheduler.ScheduledOutcomeRelay;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares {@code sandbox.scheduled.events} explicitly (auto-create is disabled on the
 * broker, D10/M0) — mirrors payment-service's {@code KafkaTopicConfig}: the producer
 * declares its own topic.
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic sandboxScheduledEventsTopic() {
        return TopicBuilder.name(ScheduledOutcomeRelay.TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
