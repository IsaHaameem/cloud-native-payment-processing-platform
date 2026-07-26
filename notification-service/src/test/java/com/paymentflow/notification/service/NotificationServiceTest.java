package com.paymentflow.notification.service;

import com.paymentflow.common.dto.event.EventEnvelope;
import com.paymentflow.notification.domain.WebhookDelivery;
import com.paymentflow.notification.domain.WebhookEvent;
import com.paymentflow.notification.email.EmailMessage;
import com.paymentflow.notification.email.EmailSender;
import com.paymentflow.notification.event.PaymentNotificationEventPayload;
import com.paymentflow.notification.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The event-handling orchestration after M18.6's cutover: email always, canonical event
 * when the type is merchant-facing, legacy adoption before fan-out, fan-out to subscribed
 * endpoints, and dispatch only once the transaction has committed.
 *
 * <p>Rewritten from its V1 form, which asserted the single-URL delivery path that this
 * sub-milestone removed. The V1 assertions were not adjusted to keep passing — they
 * described behaviour that no longer exists, and keeping them would have meant testing a
 * fiction.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private EmailSender emailSender;
    @Mock
    private WebhookEventFactory webhookEventFactory;
    @Mock
    private LegacyEndpointAdopter legacyEndpointAdopter;
    @Mock
    private WebhookFanOutService webhookFanOutService;
    @Mock
    private WebhookDispatcher webhookDispatcher;
    @Mock
    private TransactionTemplate transactionTemplate;

    private NotificationService notificationService;

    private final UUID merchantId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(processedEventRepository, emailSender, webhookEventFactory,
                legacyEndpointAdopter, webhookFanOutService, webhookDispatcher, transactionTemplate);

        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    private EventEnvelope<PaymentNotificationEventPayload> envelope(String legacyWebhookUrl) {
        PaymentNotificationEventPayload payload = new PaymentNotificationEventPayload(
                paymentId, merchantId, 5000, "USD", "AUTHORIZED", "CREATED", 5000,
                "billing@acme.test", legacyWebhookUrl);
        return EventEnvelope.of("PaymentAuthorized", paymentId.toString(), "corr-1", "test", payload);
    }

    private WebhookEvent canonicalEvent() {
        return WebhookEvent.of(UUID.randomUUID(), merchantId, "test", "payment.authorized", "2026-08-01",
                "{}", Instant.now(), "corr-1");
    }

    @Test
    void alreadyProcessedEventIsSkippedEntirely() {
        var env = envelope("https://acme.test/hooks");
        when(processedEventRepository.existsByEventId(env.eventId())).thenReturn(true);

        notificationService.handleEvent(env);

        verify(emailSender, never()).send(any());
        verify(webhookEventFactory, never()).createFrom(any());
        verify(webhookFanOutService, never()).fanOut(any());
        verify(webhookDispatcher, never()).dispatchAll(any());
    }

    @Test
    void newEventAlwaysSendsAnEmail() {
        var env = envelope(null);
        when(processedEventRepository.existsByEventId(env.eventId())).thenReturn(false);
        when(webhookEventFactory.createFrom(any())).thenReturn(Optional.empty());

        notificationService.handleEvent(env);

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender).send(captor.capture());
        assertThat(captor.getValue().recipientEmail()).isEqualTo("billing@acme.test");
        assertThat(captor.getValue().eventId()).isEqualTo(env.eventId());
        assertThat(captor.getValue().merchantId()).isEqualTo(merchantId);
    }

    @Test
    void aMerchantFacingEventIsFannedOutAndEveryDeliveryIsDispatched() {
        var env = envelope(null);
        WebhookEvent event = canonicalEvent();
        List<WebhookDelivery> deliveries = List.of(
                WebhookDelivery.forEndpoint(UUID.randomUUID(), UUID.randomUUID(), merchantId, "test", "https://a/1"),
                WebhookDelivery.forEndpoint(UUID.randomUUID(), UUID.randomUUID(), merchantId, "test", "https://a/2"));
        when(processedEventRepository.existsByEventId(env.eventId())).thenReturn(false);
        when(webhookEventFactory.createFrom(any())).thenReturn(Optional.of(event));
        when(webhookFanOutService.fanOut(event)).thenReturn(deliveries);

        notificationService.handleEvent(env);

        verify(webhookDispatcher).dispatchAll(deliveries);
        verify(processedEventRepository).save(any());
    }

    @Test
    void anInternalEventWithNoMerchantFacingCounterpartIsNeverFannedOut() {
        var env = envelope("https://acme.test/hooks");
        when(processedEventRepository.existsByEventId(env.eventId())).thenReturn(false);
        when(webhookEventFactory.createFrom(any())).thenReturn(Optional.empty());

        notificationService.handleEvent(env);

        // The email still goes out — the two channels are independent.
        verify(emailSender).send(any());
        verify(webhookFanOutService, never()).fanOut(any());
        verify(webhookDispatcher, never()).dispatchAll(any());
    }

    @Test
    void anEventWithNoSubscribedEndpointsDispatchesNothing() {
        var env = envelope(null);
        when(processedEventRepository.existsByEventId(env.eventId())).thenReturn(false);
        when(webhookEventFactory.createFrom(any())).thenReturn(Optional.of(canonicalEvent()));
        when(webhookFanOutService.fanOut(any())).thenReturn(List.of());

        notificationService.handleEvent(env);

        // "Nobody subscribed" is a normal outcome, not a failure — and it must not produce
        // an empty dispatch that a worker then has to reason about.
        verify(webhookDispatcher, never()).dispatchAll(any());
        verify(processedEventRepository).save(any());
    }

    @Test
    void theLegacyUrlIsOfferedForAdoptionBeforeFanOutSoTheFirstEventAfterCutoverIsNotLost() {
        var env = envelope("https://legacy.test/hooks");
        WebhookEvent event = canonicalEvent();
        when(processedEventRepository.existsByEventId(env.eventId())).thenReturn(false);
        when(webhookEventFactory.createFrom(any())).thenReturn(Optional.of(event));
        when(webhookFanOutService.fanOut(event)).thenReturn(List.of());

        notificationService.handleEvent(env);

        // Order matters and is the whole point (D135, moved into M18.6): an endpoint
        // adopted *after* fan-out would not receive the event that triggered its adoption,
        // so the first event following the cutover would be silently dropped.
        InOrder inOrder = inOrder(legacyEndpointAdopter, webhookFanOutService);
        inOrder.verify(legacyEndpointAdopter)
                .adoptIfNeeded(event, "https://legacy.test/hooks", "billing@acme.test");
        inOrder.verify(webhookFanOutService).fanOut(event);
    }

    @Test
    void dispatchHappensAfterTheTransactionCommitsNotInsideIt() {
        var env = envelope(null);
        WebhookEvent event = canonicalEvent();
        List<WebhookDelivery> deliveries = List.of(
                WebhookDelivery.forEndpoint(UUID.randomUUID(), UUID.randomUUID(), merchantId, "test", "https://a/1"));
        when(processedEventRepository.existsByEventId(env.eventId())).thenReturn(false);
        when(webhookEventFactory.createFrom(any())).thenReturn(Optional.of(event));
        when(webhookFanOutService.fanOut(event)).thenReturn(deliveries);

        notificationService.handleEvent(env);

        // A message published inside a transaction that then rolled back would point at a
        // delivery row that does not exist. Asserting the ordering is what keeps that
        // from silently regressing.
        InOrder inOrder = inOrder(transactionTemplate, webhookDispatcher);
        inOrder.verify(transactionTemplate).execute(any());
        inOrder.verify(webhookDispatcher).dispatchAll(eq(deliveries));
    }
}
