package com.paymentflow.notification.service;

import com.paymentflow.common.dto.event.EventEnvelope;
import com.paymentflow.notification.config.WebhookProperties;
import com.paymentflow.notification.domain.WebhookEvent;
import com.paymentflow.common.dto.event.CanonicalEventType;
import com.paymentflow.notification.event.CanonicalPaymentObject;
import com.paymentflow.notification.event.PaymentNotificationEventPayload;
import com.paymentflow.notification.event.WebhookEventBody;
import com.paymentflow.notification.repository.WebhookEventRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

/**
 * Translates an internal Kafka {@link EventEnvelope} into the canonical, merchant-facing
 * {@link WebhookEvent} (M18.3, §4.5) — the single place the platform's own vocabulary
 * becomes a public promise.
 *
 * <p>Two properties matter more than the mechanics:
 *
 * <ol>
 *   <li><b>Not every internal event is a merchant-facing one.</b> An internal type with
 *       no {@link CanonicalEventType} mapping yields {@link Optional#empty()} and is
 *       silently ignored, never treated as an error — {@code merchant.events}' key
 *       lifecycle events are audit's business, not a webhook, and a future internal event
 *       must be addable without notification-service rejecting it.</li>
 *   <li><b>One internal event yields at most one canonical event.</b> Kafka is
 *       at-least-once (D2), so redelivery must be idempotent; {@code source_event_id} is
 *       unique in the schema and checked here, so a redelivered message returns the
 *       existing row rather than creating a second {@code evt_} for the same
 *       occurrence.</li>
 * </ol>
 *
 * <p>The {@code apiVersion} stamped on the event is the platform's current one, not the
 * receiving endpoint's pin. Per-endpoint pinning (§5/M18 task 3) is a *rendering*
 * concern that M21 implements once more than one revision exists: the stored event is
 * the canonical record of what happened, and transforming it into an older revision's
 * shape at delivery time is what keeps a single stored event servable to endpoints on
 * different pins. Storing it pre-transformed per endpoint would mean N copies of one
 * occurrence, which is exactly what {@code uq_webhook_events_source_event_id} forbids.
 */
@Component
public class WebhookEventFactory {

    private final WebhookEventRepository webhookEventRepository;
    private final WebhookProperties properties;
    private final ObjectMapper objectMapper;

    public WebhookEventFactory(WebhookEventRepository webhookEventRepository, WebhookProperties properties,
                               ObjectMapper objectMapper) {
        this.webhookEventRepository = webhookEventRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Creates (or returns the already-created) canonical event for a payment envelope.
     * Empty when the internal event type has no merchant-facing counterpart.
     *
     * <p>Must be called inside the caller's transaction: the canonical event and the
     * {@code processed_events} row that marks the message handled have to commit together,
     * or a crash between them would leave an event that is never re-derivable.
     */
    public Optional<WebhookEvent> createFrom(EventEnvelope<PaymentNotificationEventPayload> envelope) {
        Optional<CanonicalEventType> eventType = CanonicalEventType.fromInternal(envelope.eventType());
        if (eventType.isEmpty()) {
            return Optional.empty();
        }

        UUID sourceEventId = envelope.eventId();
        Optional<WebhookEvent> existing = webhookEventRepository.findBySourceEventId(sourceEventId);
        if (existing.isPresent()) {
            return existing;
        }

        PaymentNotificationEventPayload payload = envelope.payload();
        // The canonical object must carry the same *resolved* mode the row stores (D125's
        // null→live reading), not the raw envelope value — otherwise the delivered body
        // and the stored row would disagree about which partition the event belongs to.
        String mode = WebhookEvent.resolveMode(envelope.mode());
        String data = objectMapper.writeValueAsString(CanonicalPaymentObject.from(payload, mode));

        return Optional.of(webhookEventRepository.save(WebhookEvent.of(
                sourceEventId,
                payload.merchantId(),
                mode,
                eventType.get().canonicalName(),
                properties.apiVersion(),
                data,
                envelope.occurredAt(),
                envelope.correlationId())));
    }

    /** Assembles the wire form a merchant receives (and, from M19, reads back from the Events API). */
    public WebhookEventBody toBody(WebhookEvent event) {
        JsonNode dataObject = objectMapper.readTree(event.getData());
        return WebhookEventBody.from(event, dataObject);
    }

    /**
     * The exact bytes that are signed and sent (M18.4 signs this string, M18.6 posts it).
     *
     * <p>Note that {@code data} is round-tripped through Jackson — read from
     * {@code jsonb} into a {@link JsonNode} and re-serialized — rather than concatenated
     * as the stored string. That is load-bearing, not incidental: Postgres normalizes
     * {@code jsonb} on write (its own key order and spacing, neither matching what
     * Jackson produced), so splicing the stored text into a body would make the delivered
     * bytes depend on Postgres's formatter. Re-serializing through Jackson makes the
     * output a function of the data alone, so the same event signs identically on every
     * attempt, on every node, and after any database round trip — which is exactly what a
     * receiver re-computing the HMAC over the body it received depends on.
     */
    public String serialize(WebhookEvent event) {
        return objectMapper.writeValueAsString(toBody(event));
    }
}
