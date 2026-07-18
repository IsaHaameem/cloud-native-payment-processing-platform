package com.paymentflow.notification.service;

import com.paymentflow.common.dto.event.EventEnvelope;
import com.paymentflow.notification.domain.EmailLogEntry;
import com.paymentflow.notification.domain.WebhookDelivery;
import com.paymentflow.notification.event.PaymentNotificationEventPayload;
import com.paymentflow.notification.repository.EmailLogEntryRepository;
import com.paymentflow.notification.repository.ProcessedEventRepository;
import com.paymentflow.notification.repository.WebhookDeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private EmailLogEntryRepository emailLogEntryRepository;
    @Mock
    private WebhookDeliveryRepository webhookDeliveryRepository;
    @Mock
    private WebhookDeliveryService webhookDeliveryService;
    @Mock
    private TransactionTemplate transactionTemplate;

    private NotificationService notificationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UUID merchantId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(processedEventRepository, emailLogEntryRepository,
                webhookDeliveryRepository, webhookDeliveryService, transactionTemplate, objectMapper);

        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });
        lenient().when(webhookDeliveryRepository.save(any(WebhookDelivery.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private EventEnvelope<PaymentNotificationEventPayload> envelope(String webhookUrl) {
        PaymentNotificationEventPayload payload = new PaymentNotificationEventPayload(
                paymentId, merchantId, 5000, "USD", "AUTHORIZED", "CREATED", 5000,
                "billing@acme.test", webhookUrl);
        return EventEnvelope.of("PaymentAuthorized", paymentId.toString(), "corr-1", payload);
    }

    @Test
    void alreadyProcessedEventIsSkippedEntirely() {
        var env = envelope("https://acme.test/hooks");
        when(processedEventRepository.existsByEventId(env.eventId())).thenReturn(true);

        notificationService.handleEvent(env);

        verify(emailLogEntryRepository, never()).save(any());
        verify(webhookDeliveryRepository, never()).save(any());
        verify(webhookDeliveryService, never()).attemptDelivery(any());
    }

    @Test
    void newEventAlwaysLogsAnEmail() {
        var env = envelope(null);
        when(processedEventRepository.existsByEventId(env.eventId())).thenReturn(false);

        notificationService.handleEvent(env);

        ArgumentCaptor<EmailLogEntry> captor = ArgumentCaptor.forClass(EmailLogEntry.class);
        verify(emailLogEntryRepository).save(captor.capture());
        assertThat(captor.getValue().getRecipientEmail()).isEqualTo("billing@acme.test");
        assertThat(captor.getValue().getEventId()).isEqualTo(env.eventId());
    }

    @Test
    void noWebhookUrlMeansNoDeliveryRowAndNoAttempt() {
        var env = envelope(null);
        when(processedEventRepository.existsByEventId(env.eventId())).thenReturn(false);

        notificationService.handleEvent(env);

        verify(webhookDeliveryRepository, never()).save(any());
        verify(webhookDeliveryService, never()).attemptDelivery(any());
        verify(processedEventRepository).save(any());
    }

    @Test
    void blankWebhookUrlIsTreatedTheSameAsAbsent() {
        var env = envelope("   ");
        when(processedEventRepository.existsByEventId(env.eventId())).thenReturn(false);

        notificationService.handleEvent(env);

        verify(webhookDeliveryRepository, never()).save(any());
        verify(webhookDeliveryService, never()).attemptDelivery(any());
    }

    @Test
    void aConfiguredWebhookCreatesAPendingDeliveryAndAttemptsItAfterCommit() {
        var env = envelope("https://acme.test/hooks");
        when(processedEventRepository.existsByEventId(env.eventId())).thenReturn(false);

        notificationService.handleEvent(env);

        ArgumentCaptor<WebhookDelivery> captor = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(webhookDeliveryRepository).save(captor.capture());
        WebhookDelivery saved = captor.getValue();
        assertThat(saved.getWebhookUrl()).isEqualTo("https://acme.test/hooks");
        assertThat(saved.getEventId()).isEqualTo(env.eventId());
        verify(webhookDeliveryService).attemptDelivery(saved);
        verify(processedEventRepository).save(any());
    }
}
