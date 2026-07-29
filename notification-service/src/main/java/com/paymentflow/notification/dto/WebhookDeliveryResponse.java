package com.paymentflow.notification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.paymentflow.notification.domain.DeliveryStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One delivery and its attempts — the object the delivery-log API returns (M18.8, §4.5).
 * This is what a developer reads when a webhook did not arrive, so it deliberately
 * exposes the whole history rather than a summary: the status a merchant needs is almost
 * never "failed", it is "failed with 502 and this response body, four times".
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WebhookDeliveryResponse(
        @Schema(description = "Unique identifier for this delivery.")
        UUID id,

        @Schema(description = "Always `webhook_delivery`.", example = "webhook_delivery")
        String object,

        @Schema(description = """
                The event being delivered. The same `evt_` id you can read back at \
                `/v1/events`, so a delivery can always be traced to what caused it.""",
                example = "evt_9f2c1e7a4b8d4c3e8a1d2b4f6a8c05d1")
        String eventId,

        @Schema(description = "The type of the event being delivered.",
                example = "payment.captured")
        String eventType,

        @Schema(description = "The endpoint this delivery is addressed to.")
        UUID endpointId,

        @Schema(description = "The URL it is being sent to, as it was when the delivery was "
                + "created.", example = "https://example.com/webhooks/paymentflow")
        String url,

        @Schema(description = """
                Where the delivery stands: still pending, delivered, retrying, or \
                dead-lettered after exhausting the retry schedule.""",
                example = "DELIVERED")
        DeliveryStatus status,

        @Schema(description = "How many attempts have been made so far.", example = "1")
        int attemptCount,

        @Schema(description = "When the next retry is scheduled. Absent once the delivery has "
                + "succeeded or been dead-lettered.")
        Instant nextAttemptAt,

        /** Set when this delivery is a replay — the delivery it re-sends (M18.8). */
        @Schema(description = "The delivery this one re-sends, when it was created by a "
                + "replay. Absent on an original delivery.")
        UUID replayedFromDeliveryId,

        @Schema(description = "When the delivery was created, as RFC 3339.")
        Instant createdAt,

        @Schema(description = "When it was last attempted. Absent if it has not been attempted "
                + "yet.")
        Instant lastAttemptedAt,

        @Schema(description = """
                Every attempt, with the request sent and the response received. This is the \
                point of the delivery log: the answer a merchant needs is almost never \
                "failed", it is "failed with 502 and this body, four times".""")
        List<WebhookDeliveryAttemptResponse> attempts) {

    /** The discriminator carried by every webhook-delivery object. */
    public static final String OBJECT_TYPE = "webhook_delivery";
}
