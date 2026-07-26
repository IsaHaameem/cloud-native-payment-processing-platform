package com.paymentflow.analytics.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/** One hour of the series (M19.6). {@code bucketStart} is inclusive; the bucket covers one hour from it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnalyticsBucketResponse(
        String object,
        Instant bucketStart,
        String currency,
        long createdCount,
        long authorizedCount,
        long capturedCount,
        long refundedCount,
        long voidedCount,
        long failedCount,
        long totalCapturedAmountMinor,
        long totalRefundedAmountMinor) {

    public static final String OBJECT_TYPE = "analytics_bucket";
}
