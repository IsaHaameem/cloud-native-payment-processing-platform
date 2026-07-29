package com.paymentflow.analytics.dto;

import io.swagger.v3.oas.annotations.media.Schema;

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
        @Schema(description = "The first day these totals cover, inclusive.",
                example = "2026-07-01")
        LocalDate from,

        @Schema(description = "The last day these totals cover, inclusive.",
                example = "2026-07-29")
        LocalDate to,

        @Schema(description = "How many API requests you made across the range.",
                example = "148203")
        long totalRequests,

        @Schema(description = "How many of those returned a 4xx — your requests that were "
                + "rejected.", example = "312")
        long totalClientErrors,

        @Schema(description = "How many returned a 5xx — this platform's failures, not yours.",
                example = "4")
        long totalServerErrors,

        @Schema(description = "The breakdown behind the totals, one bucket per day, key and "
                + "route.")
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
            @Schema(description = "The day this bucket covers, in UTC.", example = "2026-07-29")
            LocalDate day,

            @Schema(description = """
                    The API key the traffic was made with. Usage is grouped by key, so two \
                    keys hitting the same route on the same day produce two buckets. \
                    **Null** for traffic made with a key that has since been deleted — the \
                    usage is still a fact about your day.""",
                    types = {"string", "null"}, format = "uuid")
            java.util.UUID keyId,

            @Schema(description = "The route pattern the requests hit, not the concrete path — "
                    + "so every payment retrieval aggregates together.",
                    example = "/v1/payments/{id}")
            String route,

            @Schema(description = "How many requests fell in this bucket.", example = "4820")
            long requests,

            @Schema(description = "How many returned a 4xx.", example = "11")
            long clientErrors,

            @Schema(description = "How many returned a 5xx.", example = "0")
            long serverErrors,

            @Schema(description = "Mean server-side duration in milliseconds. Null when the "
                    + "bucket had no traffic.", types = {"integer", "null"}, format = "int64", example = "42")
            Long meanDurationMs,

            @Schema(description = "Median server-side duration in milliseconds. Null when the "
                    + "bucket had no traffic.", types = {"integer", "null"}, format = "int64", example = "31")
            Long p50DurationMs,

            @Schema(description = """
                    95th-percentile server-side duration in milliseconds. **Explicitly null**, \
                    never omitted, when the bucket had no traffic: a percentile over zero \
                    requests is unknown rather than zero.""",
                    types = {"integer", "null"}, format = "int64", example = "118")
            Long p95DurationMs,

            @Schema(description = "99th-percentile server-side duration in milliseconds. Null "
                    + "when the bucket had no traffic.", types = {"integer", "null"}, format = "int64", example = "260")
            Long p99DurationMs,

            @Schema(description = "The slowest single request in the bucket, in milliseconds.",
                    example = "812")
            long maxDurationMs) {
    }
}
