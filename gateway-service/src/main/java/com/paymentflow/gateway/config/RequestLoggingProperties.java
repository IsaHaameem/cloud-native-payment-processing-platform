package com.paymentflow.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tuning for the request-log pipeline (M20.2).
 *
 * @param enabled         master switch; off disables capture entirely, so the feature can be
 *                        shed instantly in an incident without a rollback
 * @param bufferCapacity  bounded queue depth. Bounded is the point (D109): the number chosen
 *                        trades how long a Kafka stall can last before events are dropped
 *                        against how much heap the gateway will hold on their behalf
 * @param maxBodyBytes    per-body capture cap, applied to the request and response
 *                        independently, before redaction and again as the stored length
 * @param captureBodies   whether bodies are captured at all. Separate from {@code enabled} so
 *                        the expensive half can be turned off while attribution, status and
 *                        latency — the cheap and most-used fields — keep flowing
 */
@ConfigurationProperties(prefix = "paymentflow.gateway.request-logging")
public record RequestLoggingProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("10000") int bufferCapacity,
        @DefaultValue("4096") int maxBodyBytes,
        @DefaultValue("true") boolean captureBodies) {

    public RequestLoggingProperties {
        if (bufferCapacity <= 0) {
            throw new IllegalArgumentException("request-logging buffer-capacity must be positive");
        }
        if (maxBodyBytes < 0) {
            throw new IllegalArgumentException("request-logging max-body-bytes must not be negative");
        }
    }
}
