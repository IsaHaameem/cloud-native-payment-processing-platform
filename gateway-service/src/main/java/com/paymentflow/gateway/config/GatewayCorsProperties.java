package com.paymentflow.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Origins allowed to call the gateway from a browser (e.g. the local Next.js dev server). */
@ConfigurationProperties(prefix = "paymentflow.gateway.cors")
public record GatewayCorsProperties(List<String> allowedOrigins) {

    public GatewayCorsProperties {
        allowedOrigins = (allowedOrigins == null) ? List.of() : List.copyOf(allowedOrigins);
    }
}
