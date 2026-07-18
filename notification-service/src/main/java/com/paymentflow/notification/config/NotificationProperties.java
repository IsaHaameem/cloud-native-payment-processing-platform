package com.paymentflow.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Retry/DLQ topic coordinates and webhook HTTP client timeouts (D46). */
@ConfigurationProperties(prefix = "paymentflow.notification")
public record NotificationProperties(
        String retryTopic,
        String retryGroupId,
        int retryListenerConcurrency,
        String dlqTopic,
        int webhookConnectTimeoutMs,
        int webhookReadTimeoutMs) {
}
