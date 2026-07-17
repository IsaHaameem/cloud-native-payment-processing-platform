package com.paymentflow.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Credentials for the locally-seeded admin account (see {@code application-local.yaml}). */
@ConfigurationProperties(prefix = "paymentflow.dev.admin")
public record DevAdminProperties(
        boolean enabled,
        String email,
        String password,
        String fullName) {
}
