package com.paymentflow.notification.listener;

import com.paymentflow.notification.config.NotificationProperties;
import com.paymentflow.notification.domain.DeliveryStatus;
import com.paymentflow.notification.domain.WebhookDelivery;
import com.paymentflow.notification.repository.WebhookDeliveryRepository;
import com.paymentflow.notification.service.WebhookDeliveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookRetryListenerTest {

    @Mock
    private WebhookDeliveryRepository webhookDeliveryRepository;
    @Mock
    private WebhookDeliveryService webhookDeliveryService;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private WebhookRetryListener listener;

    @BeforeEach
    void setUp() {
        NotificationProperties properties = new NotificationProperties(
                "payment.events.retry", "group", 3, "payment.events.dlq", 3000, 5000);
        listener = new WebhookRetryListener(webhookDeliveryRepository, webhookDeliveryService, kafkaTemplate, properties);
    }

    @Test
    void malformedEventIdIsDroppedWithoutTouchingAnything() {
        listener.onMessage("not-a-uuid");

        verify(webhookDeliveryRepository, never()).findByEventId(any());
        verify(webhookDeliveryService, never()).attemptDelivery(any());
    }

    @Test
    void unknownEventIdIsANoOp() {
        UUID eventId = UUID.randomUUID();
        when(webhookDeliveryRepository.findByEventId(eventId)).thenReturn(Optional.empty());

        listener.onMessage(eventId.toString());

        verify(webhookDeliveryService, never()).attemptDelivery(any());
    }

    @Test
    void alreadyResolvedDeliveryIsANoOp() {
        UUID eventId = UUID.randomUUID();
        WebhookDelivery delivered = WebhookDelivery.pending(eventId, UUID.randomUUID(), "https://acme.test/hooks", "{}");
        delivered.markDelivered();
        when(webhookDeliveryRepository.findByEventId(eventId)).thenReturn(Optional.of(delivered));

        listener.onMessage(eventId.toString());

        verify(webhookDeliveryService, never()).attemptDelivery(any());
    }

    @Test
    void exhaustedAttemptsAreDeadLetteredInsteadOfRetried() {
        UUID eventId = UUID.randomUUID();
        WebhookDelivery delivery = WebhookDelivery.pending(eventId, UUID.randomUUID(), "https://acme.test/hooks", "{}");
        for (int i = 0; i < 5; i++) {
            delivery.recordFailedAttempt();
        }
        when(webhookDeliveryRepository.findByEventId(eventId)).thenReturn(Optional.of(delivery));

        listener.onMessage(eventId.toString());

        verify(webhookDeliveryService, never()).attemptDelivery(any());
        verify(kafkaTemplate).send("payment.events.dlq", eventId.toString());
        assert delivery.getStatus() == DeliveryStatus.DEAD_LETTERED;
    }

    @Test
    void anAttemptBelowTheLimitIsRetried() {
        UUID eventId = UUID.randomUUID();
        WebhookDelivery delivery = WebhookDelivery.pending(eventId, UUID.randomUUID(), "https://acme.test/hooks", "{}");
        when(webhookDeliveryRepository.findByEventId(eventId)).thenReturn(Optional.of(delivery));

        listener.onMessage(eventId.toString());

        verify(webhookDeliveryService, times(1)).attemptDelivery(delivery);
        verify(kafkaTemplate, never()).send(any(), any());
    }
}
