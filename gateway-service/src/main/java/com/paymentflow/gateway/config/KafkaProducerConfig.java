package com.paymentflow.gateway.config;

import com.paymentflow.gateway.logging.ApiRequestEventPublisher;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * The gateway's Kafka producer (M20.2) — its only Kafka role, and deliberately only a
 * producer: the edge emits {@code api.request.events} and consumes nothing, so there is no
 * consumer group, no rebalancing, and nothing on a request path that can wait for a broker.
 *
 * <p>Mirrors payment-service's {@code KafkaProducerConfig} for the same reason it exists
 * there: Boot's autoconfigured template is declared {@code KafkaTemplate<Object, Object>},
 * which does not satisfy a {@code KafkaTemplate<String, String>} dependency, because
 * Spring's generic-aware autowiring matches declared type parameters rather than the
 * serializers configured via properties.
 */
@Configuration
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, String> producerFactory(KafkaProperties kafkaProperties) {
        return new DefaultKafkaProducerFactory<>(
                kafkaProperties.buildProducerProperties(), new StringSerializer(), new StringSerializer());
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    /**
     * Auto-create is disabled on the broker (D10/M0), so every topic a service produces to is
     * declared by that service.
     *
     * <p>Six partitions, matching {@code webhook.deliveries} rather than the three used by
     * the payment topics: this is the highest-volume topic on the platform by construction —
     * one message per API request — and the messages are keyed by merchant, so partitions are
     * what let one merchant's traffic be consumed in parallel with another's while each
     * merchant's own requests stay ordered.
     */
    @Bean
    public NewTopic apiRequestEventsTopic() {
        return TopicBuilder.name(ApiRequestEventPublisher.TOPIC)
                .partitions(6)
                .replicas(1)
                .build();
    }
}
