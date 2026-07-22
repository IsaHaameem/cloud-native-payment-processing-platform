package com.paymentflow.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** TTLs for email-verification and password-reset tokens (M15, task 11). */
@ConfigurationProperties(prefix = "paymentflow.identity.tokens")
public record IdentityTokenProperties(Duration emailVerificationTtl, Duration passwordResetTtl) {
}
