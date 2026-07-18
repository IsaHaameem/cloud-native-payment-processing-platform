package com.paymentflow.notification.config;

import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Boot's autoconfigured {@code KafkaTemplate} is type-erased to {@code <Object, Object>}
 * (same D-decision as payment-service's identical {@code KafkaProducerConfig}, M5) —
 * declared explicitly here so {@link com.paymentflow.notification.service.WebhookDeliveryService}
 * and the retry listener can publish to {@code payment.events.retry}/{@code .dlq}.
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
}
