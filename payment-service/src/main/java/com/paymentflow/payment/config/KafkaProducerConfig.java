package com.paymentflow.payment.config;

import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Boot's autoconfigured {@code KafkaTemplate} is declared as
 * {@code KafkaTemplate<Object, Object>} (type-erased), which doesn't satisfy a
 * {@code KafkaTemplate<String, String>} dependency — Spring's generic-aware autowiring
 * matches the bean's declared type parameters, not the serializer classes configured
 * via properties. Declaring it explicitly, still sourced from
 * {@code spring.kafka.producer.*} properties, gives {@link com.paymentflow.payment.outbox.OutboxRelay}
 * the concretely-typed template it needs.
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
