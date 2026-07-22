package com.paymentflow.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Base URL used to build email-verification/password-reset links (M15). No frontend
 * exists yet (the developer portal is M23+) — this points at where one will eventually
 * live, so the link shape and this property never have to change when it does.
 */
@ConfigurationProperties(prefix = "paymentflow.identity.action-link")
public record ActionLinkProperties(String baseUrl) {
}
