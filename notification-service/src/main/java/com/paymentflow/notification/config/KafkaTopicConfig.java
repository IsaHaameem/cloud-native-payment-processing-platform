package com.paymentflow.notification.config;

import com.paymentflow.notification.service.WebhookDispatcher;
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

    /**
     * M18.6/D106: webhook delivery gets its own topics rather than continuing to share
     * {@code payment.events.retry}. With fan-out to many endpoints and an 8-attempt,
     * ~24-hour schedule, webhook retry volume would dominate a topic other concerns
     * depend on; separate topics keep the two failure domains independent.
     *
     * <p>Partition count matters here in a way it does not for the topics above:
     * {@code WebhookDispatcher} keys by endpoint id, so partitions are what allow
     * deliveries to different endpoints to proceed in parallel while deliveries to one
     * endpoint stay ordered. Six rather than three, since fan-out multiplies message
     * volume by the number of subscribed endpoints per event.
     */
    @Bean
    public NewTopic webhookDeliveriesTopic() {
        return TopicBuilder.name(WebhookDispatcher.TOPIC)
                .partitions(6)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic webhookDeliveriesRetryTopic() {
        return TopicBuilder.name(WebhookDispatcher.TOPIC + ".retry")
                .partitions(6)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic webhookDeliveriesDlqTopic() {
        return TopicBuilder.name(WebhookDispatcher.TOPIC + ".dlq")
                .partitions(3)
                .replicas(1)
                .build();
    }
}
