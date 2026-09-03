package dev.paymentflow;

/**
 * The daily quota, as reported on a measured response.
 *
 * <p>{@code resetSeconds} is telemetry, not a retry hint: it describes the daily window even on
 * a successful response, so treating it as "wait this long" would idle a healthy client until
 * midnight UTC.
 */
public record RateLimitMeta(Long limit, Long remaining, Long resetSeconds) {}
