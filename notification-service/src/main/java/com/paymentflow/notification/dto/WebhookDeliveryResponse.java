package com.paymentflow.notification.dto;

import com.paymentflow.notification.domain.DeliveryStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One delivery and its attempts — the object the delivery-log API returns (M18.8, §4.5).
 * This is what a developer reads when a webhook did not arrive, so it deliberately
 * exposes the whole history rather than a summary: the status a merchant needs is almost
 * never "failed", it is "failed with 502 and this response body, four times".
 */
public record WebhookDeliveryResponse(
        UUID id,
        String eventId,
        String eventType,
        UUID endpointId,
        String url,
        DeliveryStatus status,
        int attemptCount,
        Instant nextAttemptAt,
        /** Set when this delivery is a replay — the delivery it re-sends (M18.8). */
        UUID replayedFromDeliveryId,
        Instant createdAt,
        Instant lastAttemptedAt,
        List<WebhookDeliveryAttemptResponse> attempts) {
}
