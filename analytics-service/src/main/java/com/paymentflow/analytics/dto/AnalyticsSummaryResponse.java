package com.paymentflow.analytics.dto;

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
        String object,
        Instant from,
        Instant to,
        long createdCount,
        long authorizedCount,
        long capturedCount,
        long refundedCount,
        long voidedCount,
        long failedCount,
        long totalCapturedAmountMinor,
        long totalRefundedAmountMinor,
        /** {@code null} when nothing was attempted in the window — a rate over zero attempts is not zero, it is unknown. */
        Double successRate,
        List<AnalyticsBucketResponse> buckets) {

    public static final String OBJECT_TYPE = "analytics_summary";

    public AnalyticsSummaryResponse {
        buckets = (buckets == null) ? List.of() : List.copyOf(buckets);
    }
}
