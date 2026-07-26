package com.paymentflow.notification.listener;

import com.paymentflow.notification.service.WebhookDeliveryProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Consumes {@code webhook.deliveries} and performs one attempt per message (M18.6,
 * D134) — the first attempt of every fan-out delivery, and every replay (M18.8).
 *
 * <p>Concurrency here is the bound on how many outbound deliveries run at once, which is
 * why it is configured rather than left at Kafka's default: this is the shared pool that
 * one merchant's slow endpoint must not be able to consume (M18's own risk table).
 * Ordering per endpoint is preserved by {@code WebhookDispatcher} keying on endpoint id,
 * so raising concurrency parallelises across endpoints, never within one.
 *
 * <p>Malformed messages are logged and dropped rather than retried, matching every other
 * listener in this service: a message that is not a UUID will never become one.
 */
@Component
public class WebhookDeliveryListener {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryListener.class);

    private final WebhookDeliveryProcessor processor;

    public WebhookDeliveryListener(WebhookDeliveryProcessor processor) {
        this.processor = processor;
    }

    @KafkaListener(
            topics = "#{T(com.paymentflow.notification.service.WebhookDispatcher).TOPIC}",
            groupId = "${paymentflow.webhooks.delivery-group-id}",
            concurrency = "${paymentflow.webhooks.max-concurrent-deliveries}")
    public void onMessage(String deliveryIdRaw) {
        UUID deliveryId;
        try {
            deliveryId = UUID.fromString(deliveryIdRaw);
        } catch (IllegalArgumentException e) {
            log.error("Could not parse a webhook dispatch message as a delivery id, dropping: {}", deliveryIdRaw, e);
            return;
        }
        processor.process(deliveryId);
    }
}
