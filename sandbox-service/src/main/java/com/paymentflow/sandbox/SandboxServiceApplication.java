package com.paymentflow.sandbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Entry point for the Sandbox Service — the platform's simulated acquirer (M17, D103). */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class SandboxServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SandboxServiceApplication.class, args);
    }
}
