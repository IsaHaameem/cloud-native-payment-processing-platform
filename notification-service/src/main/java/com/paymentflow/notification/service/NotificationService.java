package com.paymentflow.notification.service;

import com.paymentflow.common.dto.event.EventEnvelope;
import com.paymentflow.notification.domain.ProcessedEvent;
import com.paymentflow.notification.domain.WebhookDelivery;
import com.paymentflow.notification.email.EmailMessage;
import com.paymentflow.notification.email.EmailSender;
import com.paymentflow.notification.event.PaymentNotificationEventPayload;
import com.paymentflow.notification.repository.ProcessedEventRepository;
import com.paymentflow.notification.repository.WebhookDeliveryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Handles one payment lifecycle event: sends a simulated email (always, D45) via the
 * {@link EmailSender} seam and, if the merchant has a webhook configured, durably
 * records delivery intent before attempting the first (synchronous) delivery. Row
 * writes happen in a short transaction with no network I/O inside it; the first
 * delivery attempt happens only after that transaction commits (D46) — an external
 * HTTP call has no place inside a DB transaction.
 */
@Service
public class NotificationService {

    private final ProcessedEventRepository processedEventRepository;
    private final EmailSender emailSender;
    private final WebhookDeliveryRepository webhookDeliveryRepository;
    private final WebhookDeliveryService webhookDeliveryService;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public NotificationService(ProcessedEventRepository processedEventRepository,
                               EmailSender emailSender,
                               WebhookDeliveryRepository webhookDeliveryRepository,
                               WebhookDeliveryService webhookDeliveryService,
                               TransactionTemplate transactionTemplate, ObjectMapper objectMapper) {
        this.processedEventRepository = processedEventRepository;
        this.emailSender = emailSender;
        this.webhookDeliveryRepository = webhookDeliveryRepository;
        this.webhookDeliveryService = webhookDeliveryService;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    public void handleEvent(EventEnvelope<PaymentNotificationEventPayload> envelope) {
        WebhookDelivery createdDelivery = transactionTemplate.execute(status -> {
            if (processedEventRepository.existsByEventId(envelope.eventId())) {
                return null;
            }

            PaymentNotificationEventPayload payload = envelope.payload();
            emailSender.send(new EmailMessage(envelope.eventId(), payload.merchantId(), envelope.mode(),
                    payload.merchantContactEmail(), subjectFor(envelope.eventType()),
                    bodyFor(envelope.eventType(), payload), envelope.eventType()));

            WebhookDelivery delivery = null;
            String webhookUrl = payload.merchantWebhookUrl();
            if (webhookUrl != null && !webhookUrl.isBlank()) {
                String deliveryPayload = objectMapper.writeValueAsString(envelope);
                delivery = webhookDeliveryRepository.save(WebhookDelivery.pending(
                        envelope.eventId(), payload.merchantId(), envelope.mode(), webhookUrl, deliveryPayload));
            }

            processedEventRepository.save(ProcessedEvent.of(envelope.eventId(), envelope.eventType()));
            return delivery;
        });

        if (createdDelivery != null) {
            webhookDeliveryService.attemptDelivery(createdDelivery);
        }
    }

    private static String subjectFor(String eventType) {
        return "Payment update: " + eventType;
    }

    private static String bodyFor(String eventType, PaymentNotificationEventPayload payload) {
        return "Your payment " + payload.paymentId() + " changed status to " + payload.status()
                + " (event: " + eventType + ", amount: " + payload.eventAmountMinor() + " " + payload.currency() + ").";
    }
}
