package dev.paymentflow.model;

import java.util.List;

/**
 * One webhook delivery, with every {@code attempts} entry — the request sent and the response
 * received. That detail is the point of the log: the answer a merchant needs is almost never
 * "failed", it is "failed with 502 and this body, four times". {@code eventId} is the same
 * {@code evt_} id readable at {@code /v1/events}. {@code status}: see
 * {@link Vocabularies#WEBHOOK_DELIVERY_RESPONSE_STATUS_VALUES}.
 */
public record WebhookDeliveryResponse(
        Long attemptCount,
        List<WebhookDeliveryAttemptResponse> attempts,
        String createdAt,
        String endpointId,
        String eventId,
        String eventType,
        String id,
        String lastAttemptedAt,
        String nextAttemptAt,
        String object,
        String replayedFromDeliveryId,
        String status,
        String url) {}
