package com.paymentflow.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Coordinates for the downstream Merchant Service — the API-key verification call target (M15). */
@ConfigurationProperties(prefix = "paymentflow.services.merchant")
public record MerchantServiceProperties(String baseUri) {
}
