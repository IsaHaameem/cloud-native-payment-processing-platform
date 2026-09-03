package dev.paymentflow.model;

import java.util.List;

/**
 * Payment activity summarized over a window, with the hourly series behind it in {@code buckets}.
 *
 * <p>{@code successRate} is {@code authorizedCount / (authorizedCount + failedCount)}, between 0
 * and 1, and is <b>explicitly {@code null}</b> — never {@code 0} — when nothing was attempted in
 * the window: a rate over zero attempts is unknown, not zero. {@code object} is always
 * {@code analytics_summary}.
 */
public record AnalyticsSummaryResponse(
        Long authorizedCount,
        List<AnalyticsBucketResponse> buckets,
        Long capturedCount,
        Long createdCount,
        Long failedCount,
        String from,
        String object,
        Long refundedCount,
        Double successRate,
        String to,
        Long totalCapturedAmountMinor,
        Long totalRefundedAmountMinor,
        Long voidedCount) {}
