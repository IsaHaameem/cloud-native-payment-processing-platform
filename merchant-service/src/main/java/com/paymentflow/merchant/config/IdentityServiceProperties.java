package com.paymentflow.merchant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Coordinates for validating access tokens against identity-service's JWKS.
 * merchant-service never issues tokens of its own — identity is the platform's sole
 * issuer (D17: per-service zero-trust; each service validates independently).
 */
@ConfigurationProperties(prefix = "paymentflow.services.identity")
public record IdentityServiceProperties(String jwksUri, String issuer) {
}
