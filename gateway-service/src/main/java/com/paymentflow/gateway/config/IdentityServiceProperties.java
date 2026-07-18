package com.paymentflow.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Coordinates for the downstream Identity Service: the base URI routes proxy to, the
 * JWKS endpoint the gateway validates access tokens against, and the expected token
 * issuer. Identity is the platform's sole token issuer, so this single properties
 * object is enough — it is revisited if a second issuer is ever introduced.
 */
@ConfigurationProperties(prefix = "paymentflow.services.identity")
public record IdentityServiceProperties(String baseUri, String jwksUri, String issuer) {
}
