package com.paymentflow.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.kafka.annotation.EnableKafka;

/** Entry point for the Audit Service — an immutable, idempotent event sink. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableKafka
public class AuditServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuditServiceApplication.class, args);
    }
}
