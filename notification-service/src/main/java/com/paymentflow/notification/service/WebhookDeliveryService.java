package com.paymentflow.notification.service;

import com.paymentflow.notification.config.NotificationProperties;
import com.paymentflow.notification.domain.WebhookDelivery;
import com.paymentflow.notification.repository.WebhookDeliveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Makes the actual HTTP POST to a merchant's webhook URL — called both for the first,
 * synchronous attempt (right after {@code NotificationService} commits) and for every
 * subsequent retry (from {@code WebhookRetryListener}). On failure it never throws back
 * to its caller: it records the failed attempt and hands the event off to
 * {@code payment.events.retry} for the next attempt, fully decoupled via Kafka (D46).
 */
@Service
public class WebhookDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);

    private final WebhookDeliveryRepository webhookDeliveryRepository;
    private final RestClient webhookRestClient;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final NotificationProperties properties;

    public WebhookDeliveryService(WebhookDeliveryRepository webhookDeliveryRepository, RestClient webhookRestClient,
                                  KafkaTemplate<String, String> kafkaTemplate, NotificationProperties properties) {
        this.webhookDeliveryRepository = webhookDeliveryRepository;
        this.webhookRestClient = webhookRestClient;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    public void attemptDelivery(WebhookDelivery delivery) {
        try {
            webhookRestClient.post()
                    .uri(delivery.getWebhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(delivery.getPayload())
                    .retrieve()
                    .toBodilessEntity();
            delivery.markDelivered();
            webhookDeliveryRepository.save(delivery);
        } catch (Exception e) {
            delivery.recordFailedAttempt();
            webhookDeliveryRepository.save(delivery);
            log.warn("Webhook delivery failed for event {} (attempt {}): {}",
                    delivery.getEventId(), delivery.getAttemptCount(), e.toString());
            kafkaTemplate.send(properties.retryTopic(), delivery.getEventId().toString());
        }
    }
}
