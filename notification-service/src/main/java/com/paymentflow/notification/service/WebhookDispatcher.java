package com.paymentflow.notification.service;

import com.paymentflow.notification.domain.WebhookDelivery;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.UUID;

/**
 * Publishes a delivery to {@code webhook.deliveries} for a worker to attempt (M18.6,
 * D134). The message is just the delivery id — the row is the single source of truth for
 * the endpoint, the event, and the attempt count, so nothing needs carrying over Kafka
 * twice. Exactly the shape V1's retry topic already uses (D46), applied to the first
 * attempt as well as later ones.
 *
 * <p>This is the change D134 records: V1 made the first attempt inline on the
 * {@code payment.events} consumer thread, which was correct when a merchant had one URL
 * and is not when they may have sixteen. Publishing instead keeps that consumer's work
 * bounded regardless of how many endpoints a merchant registers.
 *
 * <p>Called <em>after</em> the fan-out transaction commits, never inside it: a message
 * published from inside a transaction that then rolls back would point at a delivery row
 * that does not exist.
 */
@Component
public class WebhookDispatcher {

    /** Declared in {@code WebhookKafkaConfig} — auto-create is disabled on the broker (D10/M0). */
    public static final String TOPIC = "webhook.deliveries";

    private final KafkaTemplate<String, String> kafkaTemplate;

    public WebhookDispatcher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void dispatchAll(Collection<WebhookDelivery> deliveries) {
        deliveries.forEach(delivery -> dispatch(delivery.getId(), delivery.getEndpointId()));
    }

    /**
     * Keyed by endpoint id, so every delivery to one endpoint lands on the same partition
     * and is therefore attempted in order. A merchant watching their own endpoint sees
     * events in the order the platform produced them, rather than an arbitrary
     * interleaving — and one busy endpoint cannot reorder another's.
     */
    public void dispatch(UUID deliveryId, UUID endpointId) {
        kafkaTemplate.send(TOPIC, endpointId == null ? null : endpointId.toString(), deliveryId.toString());
    }
}
