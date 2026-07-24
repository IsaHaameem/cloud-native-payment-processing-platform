package com.paymentflow.sandbox.config;

import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Mirrors payment-service's {@code KafkaProducerConfig} exactly (M17.6, this service's
 * first Kafka role): Boot's autoconfigured {@code KafkaTemplate<Object, Object>}
 * doesn't satisfy a {@code KafkaTemplate<String, String>} dependency, so it's declared
 * explicitly here, still sourced from {@code spring.kafka.producer.*} properties, for
 * {@link com.paymentflow.sandbox.scheduler.ScheduledOutcomeRelay}.
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
