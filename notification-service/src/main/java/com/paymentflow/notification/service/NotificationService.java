package com.paymentflow.notification.service;

import com.paymentflow.common.dto.event.EventEnvelope;
import com.paymentflow.notification.domain.ProcessedEvent;
import com.paymentflow.notification.domain.WebhookDelivery;
import com.paymentflow.notification.email.EmailMessage;
import com.paymentflow.notification.email.EmailSender;
import com.paymentflow.notification.event.PaymentNotificationEventPayload;
import com.paymentflow.notification.repository.ProcessedEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Handles one payment lifecycle event: sends a simulated email (always, D45) via the
 * {@link EmailSender} seam and, if the merchant has a webhook configured, durably
 * records delivery intent before attempting the first (synchronous) delivery. Row
 * writes happen in a short transaction with no network I/O inside it; the first
 * delivery attempt happens only after that transaction commits (D46) — an external
 * HTTP call has no place inside a DB transaction.
 *
 * <p><b>M18.6 is the cutover.</b> V1's "one delivery to {@code merchantWebhookUrl},
 * attempted inline post-commit" is gone. In its place: the canonical event is written,
 * fanned out to every subscribed endpoint, and each resulting delivery is dispatched
 * through {@code webhook.deliveries} once the transaction commits (D134). The
 * transaction's shape is unchanged in the way that matters — still no network I/O inside
 * it (D46) — but the work it does after committing is now bounded by a Kafka publish per
 * delivery rather than by N synchronous HTTP calls on this consumer's thread.
 *
 * <p>{@code merchantWebhookUrl} is still read from the event, for exactly one purpose:
 * adopting it into a real endpoint the first time it is seen for a merchant with none
 * (D135, {@link LegacyEndpointAdopter}). It is no longer a delivery target.
 */
@Service
public class NotificationService {

    private final ProcessedEventRepository processedEventRepository;
    private final EmailSender emailSender;
    private final WebhookEventFactory webhookEventFactory;
    private final LegacyEndpointAdopter legacyEndpointAdopter;
    private final WebhookFanOutService webhookFanOutService;
    private final WebhookDispatcher webhookDispatcher;
    private final TransactionTemplate transactionTemplate;

    public NotificationService(ProcessedEventRepository processedEventRepository,
                               EmailSender emailSender,
                               WebhookEventFactory webhookEventFactory,
                               LegacyEndpointAdopter legacyEndpointAdopter,
                               WebhookFanOutService webhookFanOutService,
                               WebhookDispatcher webhookDispatcher,
                               TransactionTemplate transactionTemplate) {
        this.processedEventRepository = processedEventRepository;
        this.emailSender = emailSender;
        this.webhookEventFactory = webhookEventFactory;
        this.legacyEndpointAdopter = legacyEndpointAdopter;
        this.webhookFanOutService = webhookFanOutService;
        this.webhookDispatcher = webhookDispatcher;
        this.transactionTemplate = transactionTemplate;
    }

    public void handleEvent(EventEnvelope<PaymentNotificationEventPayload> envelope) {
        List<WebhookDelivery> createdDeliveries = transactionTemplate.execute(status -> {
            if (processedEventRepository.existsByEventId(envelope.eventId())) {
                return List.<WebhookDelivery>of();
            }

            PaymentNotificationEventPayload payload = envelope.payload();
            emailSender.send(new EmailMessage(envelope.eventId(), payload.merchantId(), envelope.mode(),
                    payload.merchantContactEmail(), subjectFor(envelope.eventType()),
                    bodyFor(envelope.eventType(), payload), envelope.eventType()));

            // The canonical event, the deliveries it fans out to, and the processed-event
            // marker all commit together: a crash between any two would leave an event
            // that is never delivered and never retried.
            List<WebhookDelivery> deliveries = webhookEventFactory.createFrom(envelope)
                    .map(event -> {
                        legacyEndpointAdopter.adoptIfNeeded(event, payload.merchantWebhookUrl(),
                                payload.merchantContactEmail());
                        return webhookFanOutService.fanOut(event);
                    })
                    .orElseGet(List::of);

            processedEventRepository.save(ProcessedEvent.of(envelope.eventId(), envelope.eventType()));
            return deliveries;
        });

        // Dispatched only after the transaction commits — a message published from inside
        // a transaction that then rolled back would point at a row that does not exist.
        if (createdDeliveries != null && !createdDeliveries.isEmpty()) {
            webhookDispatcher.dispatchAll(createdDeliveries);
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
