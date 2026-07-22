package com.paymentflow.merchant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunables for the M15 multi-key model: how long a rotated-out key keeps
 * authenticating before it goes inactive on its own (see {@code ApiKey.rotateWithGrace}),
 * and how often {@code last_used_at} is allowed to actually write to the database for
 * one key (never once per request — task 2's explicit constraint).
 */
@ConfigurationProperties(prefix = "paymentflow.merchant.api-key")
public record ApiKeyProperties(Duration rotationGracePeriod, Duration lastUsedThrottle) {
}
