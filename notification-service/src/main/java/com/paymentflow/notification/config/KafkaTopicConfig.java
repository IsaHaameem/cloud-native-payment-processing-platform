package com.paymentflow.notification.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the topics notification-service itself produces to — auto-create is
 * disabled on the broker (D10/M0). {@code payment.events} isn't declared here: it's
 * payment-service's topic (M5); notification-service only consumes it.
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic paymentEventsRetryTopic(NotificationProperties properties) {
        return TopicBuilder.name(properties.retryTopic())
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic paymentEventsDlqTopic(NotificationProperties properties) {
        return TopicBuilder.name(properties.dlqTopic())
                .partitions(3)
                .replicas(1)
                .build();
    }
}
