package dev.paymentflow.model;

/**
 * One day of API usage for one key and one route pattern. The percentile and mean durations are
 * <b>explicitly {@code null}</b> when the bucket had no traffic — a percentile over zero requests
 * is unknown, not zero. {@code keyId} is {@code null} for traffic made with a key since deleted.
 */
public record UsageBucketResponse(
        Long clientErrors,
        String day,
        String keyId,
        Long maxDurationMs,
        Double meanDurationMs,
        Double p50DurationMs,
        Double p95DurationMs,
        Double p99DurationMs,
        Long requests,
        String route,
        Long serverErrors) {}
