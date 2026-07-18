package com.paymentflow.payment.config;

import com.paymentflow.payment.event.PaymentEventPublisher;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares topics explicitly (auto-create is disabled on the broker — D10/M0) rather
 * than relying on implicit creation. Retry/DLQ topics are added when a consumer that
 * actually needs them exists (M6+), not speculatively here.
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic paymentEventsTopic() {
        return TopicBuilder.name(PaymentEventPublisher.TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
