package com.paymentflow.notification.listener;

import com.paymentflow.common.dto.event.EventEnvelope;
import com.paymentflow.notification.event.PaymentNotificationEventPayload;
import com.paymentflow.notification.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code payment.events} (D5's convention: consumer group
 * {@code <service>-<topic>}). Malformed messages are logged and dropped, not
 * retried — the same defensive behavior as transaction-service's/audit-service's
 * listeners; the retry topic exists only for delivery failures of a message that
 * parsed and was already durably recorded.
 */
@Component
public class NotificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public NotificationEventListener(NotificationService notificationService, ObjectMapper objectMapper) {
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${paymentflow.kafka.payment-events-topic}",
            concurrency = "${paymentflow.kafka.listener-concurrency}")
    public void onMessage(String json) {
        EventEnvelope<PaymentNotificationEventPayload> envelope;
        try {
            envelope = objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructParametricType(EventEnvelope.class, PaymentNotificationEventPayload.class));
        } catch (Exception e) {
            log.error("Could not parse payment event, dropping: {}", json, e);
            return;
        }

        notificationService.handleEvent(envelope);
    }
}
