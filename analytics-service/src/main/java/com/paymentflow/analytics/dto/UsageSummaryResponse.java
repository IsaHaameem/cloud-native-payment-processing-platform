package com.paymentflow.analytics.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Usage for a date range: totals, plus the per-day / per-route breakdown (M20.6).
 *
 * <p>Totals and breakdown travel together for the same reason M19.6's analytics response
 * carries totals alongside its series — a dashboard renders both on one screen, and splitting
 * them across endpoints would mean two queries over the same rows.
 *
 * <p>Deliberately <b>not</b> {@code @JsonInclude(NON_NULL)}: D143 established that omitting a
 * null makes "we measured and there is no answer" indistinguishable from "this version has no
 * such field". {@code p95DurationMs} is genuinely null for a day with no traffic, and that is
 * the case the field exists to communicate.
 */
public record UsageSummaryResponse(
        LocalDate from,
        LocalDate to,
        long totalRequests,
        long totalClientErrors,
        long totalServerErrors,
        List<UsageBucketResponse> buckets) {

    /**
     * One (day, key, route) bucket.
     *
     * <p><b>{@code keyId} is on the wire because the aggregate is grouped by it.</b> §5/M20 asks
     * for usage "per key, per endpoint, per day", and `api_usage_daily`'s unique constraint
     * includes the key — so a merchant using two keys against the same route on the same day has
     * two rows. Without this field those rows are indistinguishable in the response: identical
     * day, identical route, different numbers, and no way to tell why. Found by M20.6's own test
     * rather than by review, which reported two buckets where it expected one.
     *
     * <p>Aggregating across keys instead was rejected: it would discard the per-key breakdown the
     * milestone lists as a feature, and the percentiles could not be combined anyway — summing
     * counts is valid, averaging p95s is not.
     *
     * <p>Null for traffic recorded against a key that has since been deleted; the usage remains a
     * fact about the merchant's day even when the key it was made with is gone.
     *
     * @param meanDurationMs derived here rather than stored, from the sum and count the rollup
     *                       kept for exactly this purpose
     * @param p95DurationMs  computed at rollup time from the raw rows; null when the day had no
     *                       traffic, because a percentile over zero requests is unknown rather
     *                       than zero (D143's reasoning, applied again)
     */
    public record UsageBucketResponse(
            LocalDate day,
            java.util.UUID keyId,
            String route,
            long requests,
            long clientErrors,
            long serverErrors,
            Long meanDurationMs,
            Long p50DurationMs,
            Long p95DurationMs,
            Long p99DurationMs,
            long maxDurationMs) {
    }
}
