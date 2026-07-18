package com.paymentflow.analytics.event;

import java.util.UUID;

/**
 * analytics-service's own local copy of the JSON shape payment-service publishes (D36,
 * extended to a fourth consumer). Only the fields this read-model actually aggregates
 * on are carried — no contact info, unlike notification-service's copy of the same event.
 */
public record AnalyticsEventPayload(
        UUID paymentId,
        UUID merchantId,
        long amountMinor,
        String currency,
        String status,
        String previousStatus,
        long eventAmountMinor) {
}
