package com.paymentflow.analytics.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** One hour of the series (M19.6). {@code bucketStart} is inclusive; the bucket covers one hour from it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnalyticsBucketResponse(
        @Schema(description = "Always `analytics_bucket`.", example = "analytics_bucket")
        String object,

        @Schema(description = "The inclusive start of this bucket. Each bucket covers the "
                + "hour beginning here.")
        Instant bucketStart,

        @Schema(description = "The ISO 4217 currency these counts and amounts are for. Buckets "
                + "are split by currency, so amounts are never mixed.", example = "USD")
        String currency,

        @Schema(description = "Payments created in this hour.", example = "62")
        long createdCount,

        @Schema(description = "Payments authorized in this hour.", example = "58")
        long authorizedCount,

        @Schema(description = "Payments captured in this hour.", example = "57")
        long capturedCount,

        @Schema(description = "Payments refunded in this hour.", example = "2")
        long refundedCount,

        @Schema(description = "Authorizations released without capture in this hour.",
                example = "1")
        long voidedCount,

        @Schema(description = "Authorization attempts that failed in this hour.", example = "4")
        long failedCount,

        @Schema(description = "Total captured in this hour, in the bucket's currency's minor "
                + "unit.", example = "57000")
        long totalCapturedAmountMinor,

        @Schema(description = "Total refunded in this hour, in the bucket's currency's minor "
                + "unit.", example = "2000")
        long totalRefundedAmountMinor) {

    public static final String OBJECT_TYPE = "analytics_bucket";
}
