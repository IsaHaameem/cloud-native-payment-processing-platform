package com.paymentflow.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * JWT signing configuration. When {@code privateKey}/{@code publicKey} are absent an
 * ephemeral RSA keypair is generated at startup (dev convenience); in production these
 * PEM values are injected from Secrets Manager.
 */
@ConfigurationProperties(prefix = "paymentflow.security.jwt")
public record JwtProperties(
        String issuer,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        String privateKey,
        String publicKey) {

    public JwtProperties {
        if (issuer == null || issuer.isBlank()) {
            issuer = "paymentflow-identity";
        }
        if (accessTokenTtl == null) {
            accessTokenTtl = Duration.ofMinutes(15);
        }
        if (refreshTokenTtl == null) {
            refreshTokenTtl = Duration.ofDays(7);
        }
    }

    public boolean hasConfiguredKeys() {
        return privateKey != null && !privateKey.isBlank()
                && publicKey != null && !publicKey.isBlank();
    }
}
