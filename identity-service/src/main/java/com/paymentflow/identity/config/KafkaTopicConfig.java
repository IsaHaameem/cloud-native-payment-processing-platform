package com.paymentflow.identity.config;

import com.paymentflow.identity.event.IdentityEventPublisher;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Declares identity.events explicitly (auto-create is disabled on the broker — D10/M0). */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic identityEventsTopic() {
        return TopicBuilder.name(IdentityEventPublisher.TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
