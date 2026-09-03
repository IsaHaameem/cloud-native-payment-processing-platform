package dev.paymentflow.model;

import java.util.List;

/**
 * API usage summarized over a day range, with the per-day-per-key-per-route breakdown in
 * {@code buckets}. {@code totalClientErrors} are your rejected requests (4xx);
 * {@code totalServerErrors} are this platform's failures (5xx).
 */
public record UsageSummaryResponse(
        List<UsageBucketResponse> buckets,
        String from,
        String to,
        Long totalClientErrors,
        Long totalRequests,
        Long totalServerErrors) {}
