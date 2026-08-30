package dev.paymentflow.model;

/**
 * One hour of payment activity, for a single currency. Buckets are split by currency so amounts
 * are never mixed. {@code object} is always {@code analytics_bucket}.
 */
public record AnalyticsBucketResponse(
        Long authorizedCount,
        String bucketStart,
        Long capturedCount,
        Long createdCount,
        String currency,
        Long failedCount,
        String object,
        Long refundedCount,
        Long totalCapturedAmountMinor,
        Long totalRefundedAmountMinor,
        Long voidedCount) {}
