package com.paymentflow.merchant.config;

import com.paymentflow.merchant.event.MerchantEventPublisher;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Declares merchant.events explicitly (auto-create is disabled on the broker — D10/M0). */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic merchantEventsTopic() {
        return TopicBuilder.name(MerchantEventPublisher.TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
