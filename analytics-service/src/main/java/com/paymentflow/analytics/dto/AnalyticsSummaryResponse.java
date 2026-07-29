package com.paymentflow.analytics.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * Totals over a window, plus the derived rate a dashboard actually plots (M19.6).
 *
 * <p>{@code successRate} is computed here rather than left to the caller because there is
 * more than one defensible denominator, and a platform that returns raw counters invites
 * every client to pick a different one. The published definition is
 * {@code authorized / (authorized + failed)}: it measures how often an authorization
 * attempt succeeded, which is the question merchants are asking. Payments still in
 * {@code CREATED} are excluded — they have not been attempted yet, and counting them as
 * failures would make the rate drop simply because traffic arrived.
 */
/*
 * Deliberately NOT @JsonInclude(NON_NULL), unlike every other response in this platform
 * (M19.8).
 *
 * <p>The one nullable field here is {@code successRate}, and null is a *published value*
 * rather than an absence: it means "no authorization was attempted in this window, so the
 * rate is unknown". Omitting the field would make that indistinguishable from "this API
 * version does not have a successRate", which §4.10 explicitly tells clients to expect and
 * ignore — so a charting client would silently drop the one case the null exists to
 * signal. Found on the live stack: the response really did arrive with no successRate key
 * at all, while the guide promised an explicit null.
 *
 * <p>No other field here is ever null, so this changes nothing else on the wire.
 */
public record AnalyticsSummaryResponse(
        @Schema(description = "Always `analytics_summary`.", example = "analytics_summary")
        String object,

        @Schema(description = "The start of the window these totals cover, inclusive.")
        Instant from,

        @Schema(description = "The end of the window these totals cover, inclusive.")
        Instant to,

        @Schema(description = "How many payments were created in the window.", example = "1420")
        long createdCount,

        @Schema(description = "How many payments were authorized.", example = "1310")
        long authorizedCount,

        @Schema(description = "How many payments were captured.", example = "1298")
        long capturedCount,

        @Schema(description = "How many payments were refunded, in whole or in part.",
                example = "37")
        long refundedCount,

        @Schema(description = "How many authorizations were released without capture.",
                example = "12")
        long voidedCount,

        @Schema(description = "How many authorization attempts failed.", example = "110")
        long failedCount,

        @Schema(description = "Total captured across the window, in minor units. Not "
                + "currency-split — use `buckets` for that.", example = "1298000")
        long totalCapturedAmountMinor,

        @Schema(description = "Total refunded across the window, in minor units.",
                example = "37000")
        long totalRefundedAmountMinor,

        /** {@code null} when nothing was attempted in the window — a rate over zero attempts is not zero, it is unknown. */
        @Schema(description = """
                The share of authorization attempts that succeeded, between 0 and 1, computed \
                as `authorizedCount / (authorizedCount + failedCount)`. Payments still in \
                `created` are excluded — they have not been attempted, and counting them as \
                failures would make the rate fall simply because traffic arrived.

                **Explicitly `null`**, never omitted, when nothing was attempted in the \
                window: a rate over zero attempts is unknown rather than zero.""",
                // `types` and not `nullable`. M21.7 found that swagger's `nullable = true`
                // renders nothing at all in a 3.1 document — 3.0 spelled nullability as a
                // flag, 3.1 spells it as a type union, and the flag is silently dropped. The
                // field would have been published as a plain number, which is the one shape
                // it provably is not.
                types = {"number", "null"}, format = "double", example = "0.9225")
        Double successRate,

        @Schema(description = "The hourly series behind these totals, split by currency. The "
                + "series begins where this platform started recording it.")
        List<AnalyticsBucketResponse> buckets) {

    public static final String OBJECT_TYPE = "analytics_summary";

    public AnalyticsSummaryResponse {
        buckets = (buckets == null) ? List.of() : List.copyOf(buckets);
    }
}
