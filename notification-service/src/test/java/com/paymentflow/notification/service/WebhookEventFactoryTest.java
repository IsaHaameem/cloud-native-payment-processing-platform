package com.paymentflow.notification.service;

import com.paymentflow.common.dto.event.EventEnvelope;
import com.paymentflow.notification.TestWebhookProperties;
import com.paymentflow.notification.domain.WebhookEvent;
import com.paymentflow.notification.event.PaymentNotificationEventPayload;
import com.paymentflow.notification.event.WebhookEventBody;
import com.paymentflow.notification.repository.WebhookEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The internal → canonical translation (M18.3), tested without Spring or a database:
 * this is a pure mapping decision and the platform's first public event contract, so it
 * gets the same exhaustive treatment {@code DecisionEngine} and {@code ApiKeyFormat} get.
 */
class WebhookEventFactoryTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-08-01T12:00:00Z");
    private static final String API_VERSION = TestWebhookProperties.API_VERSION;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final Map<UUID, WebhookEvent> stored = new HashMap<>();
    private WebhookEventRepository repository;
    private WebhookEventFactory factory;

    @BeforeEach
    void setUp() {
        repository = mock(WebhookEventRepository.class);
        when(repository.findBySourceEventId(any())).thenAnswer(
                invocation -> Optional.ofNullable(stored.get(invocation.getArgument(0, UUID.class))));
        when(repository.save(any(WebhookEvent.class))).thenAnswer(invocation -> {
            WebhookEvent event = invocation.getArgument(0, WebhookEvent.class);
            stored.put(event.getSourceEventId(), event);
            return event;
        });
        factory = new WebhookEventFactory(repository, TestWebhookProperties.defaults(), objectMapper);
    }

    private static EventEnvelope<PaymentNotificationEventPayload> envelope(String internalEventType, String mode) {
        PaymentNotificationEventPayload payload = new PaymentNotificationEventPayload(
                UUID.randomUUID(), UUID.randomUUID(), 5000, "USD", "AUTHORIZED", "CREATED", 5000,
                "billing@acme.test", "https://sink.test/hook");
        return new EventEnvelope<>(UUID.randomUUID(), internalEventType, UUID.randomUUID().toString(),
                OCCURRED_AT, "corr-1", mode, payload);
    }

    @Test
    void everyInternalPaymentEventTypeMapsToItsCanonicalName() {
        assertThat(factory.createFrom(envelope("PaymentCreated", "test")).orElseThrow().getEventType())
                .isEqualTo("payment.created");
        assertThat(factory.createFrom(envelope("PaymentAuthorized", "test")).orElseThrow().getEventType())
                .isEqualTo("payment.authorized");
        assertThat(factory.createFrom(envelope("PaymentCaptured", "test")).orElseThrow().getEventType())
                .isEqualTo("payment.captured");
        assertThat(factory.createFrom(envelope("PaymentFailed", "test")).orElseThrow().getEventType())
                .isEqualTo("payment.failed");
        assertThat(factory.createFrom(envelope("PaymentVoided", "test")).orElseThrow().getEventType())
                .isEqualTo("payment.voided");
        assertThat(factory.createFrom(envelope("PaymentRefunded", "test")).orElseThrow().getEventType())
                .isEqualTo("payment.refunded");
        assertThat(factory.createFrom(envelope("PaymentPartiallyRefunded", "test")).orElseThrow().getEventType())
                .isEqualTo("payment.partially_refunded");
    }

    @Test
    void anInternalEventWithNoMerchantFacingCounterpartIsIgnoredRatherThanRejected() {
        // merchant.events' key lifecycle is audit's business, not a webhook. A future
        // internal event type must be addable without notification-service failing on it.
        assertThat(factory.createFrom(envelope("ApiKeyIssued", "test"))).isEmpty();
        assertThat(factory.createFrom(envelope("SomethingInventedLater", "test"))).isEmpty();
        verify(repository, never()).save(any(WebhookEvent.class));
    }

    @Test
    void redeliveryOfTheSameInternalEventReturnsTheSameCanonicalEvent() {
        EventEnvelope<PaymentNotificationEventPayload> envelope = envelope("PaymentAuthorized", "test");

        WebhookEvent first = factory.createFrom(envelope).orElseThrow();
        WebhookEvent second = factory.createFrom(envelope).orElseThrow();

        // Kafka is at-least-once (D2): one occurrence must never become two evt_ ids.
        assertThat(second.getEventRef()).isEqualTo(first.getEventRef());
        verify(repository, org.mockito.Mockito.times(1)).save(any(WebhookEvent.class));
    }

    @Test
    void anAbsentEnvelopeModeIsResolvedToLiveInBothTheRowAndTheBody() {
        WebhookEvent event = factory.createFrom(envelope("PaymentAuthorized", null)).orElseThrow();

        assertThat(event.getMode()).isEqualTo("live");
        // The row and the delivered body must agree about the partition; a body still
        // showing null while the row says live is the drift this asserts against.
        assertThat(factory.toBody(event).mode()).isEqualTo("live");
        assertThat(factory.toBody(event).data().object().get("mode").asString()).isEqualTo("live");
    }

    @Test
    void theBodyCarriesTheDocumentedEnvelopeAndANestedDataObject() {
        WebhookEvent event = factory.createFrom(envelope("PaymentCaptured", "test")).orElseThrow();

        WebhookEventBody body = factory.toBody(event);

        assertThat(body.id()).startsWith("evt_").isEqualTo(event.getEventRef());
        assertThat(body.object()).isEqualTo("event");
        assertThat(body.type()).isEqualTo("payment.captured");
        assertThat(body.apiVersion()).isEqualTo(API_VERSION);
        assertThat(body.created()).isEqualTo(OCCURRED_AT);
        assertThat(body.mode()).isEqualTo("test");
        assertThat(body.data().object().get("object").asString()).isEqualTo("payment");
        assertThat(body.data().object().get("amountMinor").asLong()).isEqualTo(5000);
        assertThat(body.data().object().get("currency").asString()).isEqualTo("USD");
        assertThat(body.data().object().get("status").asString()).isEqualTo("AUTHORIZED");
    }

    @Test
    void theBodyNeverLeaksTheMerchantsOwnContactDetailsOrWebhookUrl() {
        WebhookEvent event = factory.createFrom(envelope("PaymentAuthorized", "test")).orElseThrow();

        String serialized = factory.serialize(event);

        // These are routing fields D43 embedded for this platform's own consumers. Echoing
        // them into a body delivered to an arbitrary endpoint would be a gratuitous data
        // exposure — the reason CanonicalPaymentObject is a translation, not a passthrough.
        assertThat(serialized).doesNotContain("billing@acme.test");
        assertThat(serialized).doesNotContain("sink.test/hook");
        assertThat(serialized).doesNotContain("merchantContactEmail");
        assertThat(serialized).doesNotContain("merchantWebhookUrl");
    }

    @Test
    void serializingIsStableSoTheSignatureCoversWhatIsDelivered() {
        WebhookEvent event = factory.createFrom(envelope("PaymentAuthorized", "test")).orElseThrow();

        // M18.4 signs these exact bytes; two serializations of one event differing would
        // make a signature unverifiable against the body that was actually sent.
        assertThat(factory.serialize(event)).isEqualTo(factory.serialize(event));
    }
}
