package com.paymentflow.notification.listener;

import com.paymentflow.notification.config.NotificationProperties;
import com.paymentflow.notification.domain.DeliveryStatus;
import com.paymentflow.notification.domain.WebhookDelivery;
import com.paymentflow.notification.repository.WebhookDeliveryRepository;
import com.paymentflow.notification.service.WebhookDeliveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Consumes {@code payment.events.retry}: each message is just an event id (the
 * {@link WebhookDelivery} row, keyed by that id, is the single source of truth for the
 * URL/payload/attempt count — no need to carry them again over Kafka). Retries with
 * jittered exponential backoff up to {@link #MAX_ATTEMPTS} total delivery attempts (the
 * first of which already happened synchronously in {@code NotificationService}) before
 * dead-lettering to {@code payment.events.dlq} (D46) — mirrors transaction-service's
 * {@code LedgerService} backoff-retry shape (M6), applied to a Kafka hop instead of a DB
 * transaction retry.
 */
@Component
public class WebhookRetryListener {

    private static final Logger log = LoggerFactory.getLogger(WebhookRetryListener.class);
    private static final int MAX_ATTEMPTS = 5;
    private static final long BACKOFF_BASE_MILLIS = 1000;
    private static final long MAX_BACKOFF_MILLIS = 30_000;

    private final WebhookDeliveryRepository webhookDeliveryRepository;
    private final WebhookDeliveryService webhookDeliveryService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final NotificationProperties properties;

    public WebhookRetryListener(WebhookDeliveryRepository webhookDeliveryRepository,
                                WebhookDeliveryService webhookDeliveryService,
                                KafkaTemplate<String, String> kafkaTemplate, NotificationProperties properties) {
        this.webhookDeliveryRepository = webhookDeliveryRepository;
        this.webhookDeliveryService = webhookDeliveryService;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    @KafkaListener(
            topics = "${paymentflow.notification.retry-topic}",
            groupId = "${paymentflow.notification.retry-group-id}",
            concurrency = "${paymentflow.notification.retry-listener-concurrency}")
    public void onMessage(String eventIdRaw) {
        UUID eventId;
        try {
            eventId = UUID.fromString(eventIdRaw);
        } catch (IllegalArgumentException e) {
            log.error("Could not parse retry message as an event id, dropping: {}", eventIdRaw, e);
            return;
        }

        Optional<WebhookDelivery> maybeDelivery = webhookDeliveryRepository.findByEventId(eventId);
        if (maybeDelivery.isEmpty()) {
            log.warn("No webhook delivery row found for retried event {}, dropping", eventId);
            return;
        }

        WebhookDelivery delivery = maybeDelivery.get();
        if (delivery.getStatus() != DeliveryStatus.PENDING) {
            // Already resolved (delivered or dead-lettered) by a prior message — this
            // redelivery of the retry topic itself is an idempotent no-op.
            return;
        }

        if (delivery.getAttemptCount() >= MAX_ATTEMPTS) {
            delivery.markDeadLettered();
            webhookDeliveryRepository.save(delivery);
            kafkaTemplate.send(properties.dlqTopic(), delivery.getEventId().toString());
            log.warn("Webhook delivery for event {} dead-lettered after {} attempts",
                    eventId, delivery.getAttemptCount());
            return;
        }

        backoff(delivery.getAttemptCount());
        webhookDeliveryService.attemptDelivery(delivery);
    }

    private static void backoff(int attempt) {
        try {
            long exponential = BACKOFF_BASE_MILLIS * (1L << Math.min(attempt, 5));
            long jitterMillis = Math.min(exponential, MAX_BACKOFF_MILLIS) + (long) (Math.random() * BACKOFF_BASE_MILLIS);
            Thread.sleep(jitterMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
