package com.paymentflow.notification;

import com.paymentflow.common.dto.event.EventEnvelope;
import com.paymentflow.notification.crypto.WebhookSecretCipher;
import com.paymentflow.notification.domain.AttemptOutcome;
import com.paymentflow.notification.domain.DeliveryStatus;
import com.paymentflow.notification.domain.WebhookDelivery;
import com.paymentflow.notification.domain.WebhookDeliveryAttempt;
import com.paymentflow.notification.domain.WebhookEndpoint;
import com.paymentflow.notification.domain.WebhookSubscription;
import com.paymentflow.notification.event.PaymentNotificationEventPayload;
import com.paymentflow.notification.repository.EmailLogEntryRepository;
import com.paymentflow.notification.repository.WebhookDeliveryAttemptRepository;
import com.paymentflow.notification.repository.WebhookDeliveryRepository;
import com.paymentflow.notification.repository.WebhookEndpointRepository;
import com.paymentflow.notification.repository.WebhookEventRepository;
import com.paymentflow.notification.repository.WebhookSubscriptionRepository;
import com.paymentflow.notification.service.WebhookSigner;
import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The Kafka → fan-out → signed delivery pipeline end to end (M18.6), against a real
 * broker, real Postgres, and real JDK {@link HttpServer} sinks standing in for merchant
 * endpoints.
 *
 * <p>Rewritten from its V1 form. The old cases asserted the single-URL path this
 * sub-milestone removed — they were not adapted to keep passing, because a test that
 * describes behaviour the platform no longer has is worse than one that is missing. What
 * they were really protecting (an event produces a delivery, a redelivery does not
 * duplicate it, a malformed message does not kill the consumer, the mode is recorded) is
 * preserved below in fan-out terms.
 */
@SpringBootTest(properties = {
        "paymentflow.webhooks.require-https=false",
        "paymentflow.webhooks.allowed-hosts=localhost",
        // Deterministic and short: this suite asserts *that* a failing endpoint retries
        // and dead-letters, not how patiently it waits (the schedule's arithmetic is
        // WebhookRetryScheduleTest's job).
        "paymentflow.webhooks.retry-schedule=1s,1s,1s",
        "paymentflow.webhooks.auto-disable-after-consecutive-failures=4"
})
@Testcontainers
class NotificationIntegrationTest {

    private static final String TOPIC = "payment.events";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @Container
    static ConfluentKafkaContainer kafka =
            new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @BeforeAll
    static void createTopics() throws Exception {
        Properties adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(adminProps)) {
            admin.createTopics(List.of(
                    new NewTopic(TOPIC, 3, (short) 1),
                    new NewTopic("payment.events.retry", 3, (short) 1),
                    new NewTopic("payment.events.dlq", 3, (short) 1),
                    new NewTopic("webhook.deliveries", 6, (short) 1),
                    new NewTopic("webhook.deliveries.retry", 6, (short) 1),
                    new NewTopic("webhook.deliveries.dlq", 3, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }
    }

    // ── The merchant sinks ──────────────────────────────────────────────────────────
    private static HttpServer sink;
    private static final ConcurrentHashMap<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, List<String>> signatures = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, List<String>> bodies = new ConcurrentHashMap<>();
    private static volatile int sinkStatus = 200;

    @BeforeAll
    static void startSink() throws Exception {
        sink = HttpServer.create(new InetSocketAddress(0), 0);
        sink.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            hits.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();
            signatures.computeIfAbsent(path, key -> java.util.Collections.synchronizedList(new ArrayList<>()))
                    .add(String.valueOf(exchange.getRequestHeaders().getFirst(WebhookSigner.SIGNATURE_HEADER)));
            bodies.computeIfAbsent(path, key -> java.util.Collections.synchronizedList(new ArrayList<>()))
                    .add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(sinkStatus, -1);
            exchange.close();
        });
        sink.start();
    }

    @AfterAll
    static void stopSink() {
        if (sink != null) {
            sink.stop(0);
        }
    }

    private static String sinkUrl(String path) {
        return "http://localhost:" + sink.getAddress().getPort() + path;
    }

    @Autowired
    private EmailLogEntryRepository emailLogEntryRepository;
    @Autowired
    private WebhookEndpointRepository endpointRepository;
    @Autowired
    private WebhookSubscriptionRepository subscriptionRepository;
    @Autowired
    private WebhookEventRepository webhookEventRepository;
    @Autowired
    private WebhookDeliveryRepository deliveryRepository;
    @Autowired
    private WebhookDeliveryAttemptRepository attemptRepository;
    @Autowired
    private WebhookSecretCipher secretCipher;
    @Autowired
    private WebhookSigner signer;
    @Autowired
    private ObjectMapper objectMapper;

    private KafkaProducer<String, String> producer;

    @BeforeEach
    void setUp() {
        sinkStatus = 200;
        hits.clear();
        signatures.clear();
        bodies.clear();
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producer = new KafkaProducer<>(props);
    }

    @AfterEach
    void closeProducer() {
        producer.close();
    }

    private String rawSecret;

    private WebhookEndpoint registerEndpoint(UUID merchantId, String mode, String path, String... eventTypes) {
        rawSecret = "whsec_" + UUID.randomUUID().toString().replace("-", "");
        WebhookEndpoint endpoint = endpointRepository.save(WebhookEndpoint.register(merchantId, mode,
                sinkUrl(path), "test endpoint", TestWebhookProperties.API_VERSION,
                secretCipher.encrypt(rawSecret), rawSecret.substring(0, 12), "ops@merchant.test"));
        for (String eventType : eventTypes) {
            subscriptionRepository.save(WebhookSubscription.of(endpoint.getId(), eventType));
        }
        return endpoint;
    }

    // ── Tests ───────────────────────────────────────────────────────────────────────

    @Test
    void anEventIsFannedOutOnlyToEndpointsSubscribedToItsType() throws Exception {
        UUID merchantId = UUID.randomUUID();
        registerEndpoint(merchantId, "test", "/wants-authorized", "payment.authorized");
        registerEndpoint(merchantId, "test", "/wants-everything", "*");
        registerEndpoint(merchantId, "test", "/wants-refunds-only", "payment.refunded");

        UUID eventId = UUID.randomUUID();
        publish(eventId, "PaymentAuthorized", UUID.randomUUID(), merchantId, "AUTHORIZED", "CREATED", null, "test");

        // The milestone's own completion criterion: three endpoints with different
        // subscriptions, each receiving exactly what it subscribed to.
        await().atMost(Duration.ofSeconds(20)).until(() -> hitsOn("/wants-authorized") == 1
                && hitsOn("/wants-everything") == 1);
        assertThat(hitsOn("/wants-refunds-only")).isZero();

        // The delivery row is marked DELIVERED *after* the HTTP call returns, so waiting on
        // the sink's hit counter is not the same as waiting on the outcome being recorded.
        // Asserting the status immediately after the hits arrive was a race: it passed
        // almost always and failed under load (observed during M21.4's full build, with one
        // of the two deliveries still PENDING). Awaited rather than asserted, so the test
        // waits for the thing it is actually about.
        await().atMost(Duration.ofSeconds(20)).until(() -> {
            List<WebhookDelivery> deliveries = deliveriesForEvent(eventId);
            return deliveries.size() == 2
                    && deliveries.stream().allMatch(d -> d.getStatus() == DeliveryStatus.DELIVERED);
        });
    }

    @Test
    void everyDeliveryIsSignedAndIndependentlyVerifiable() throws Exception {
        UUID merchantId = UUID.randomUUID();
        WebhookEndpoint endpoint = registerEndpoint(merchantId, "test", "/signed", "*");
        String secret = rawSecret;

        publish(UUID.randomUUID(), "PaymentCaptured", UUID.randomUUID(), merchantId, "CAPTURED", "AUTHORIZED",
                null, "test");
        await().atMost(Duration.ofSeconds(20)).until(() -> hitsOn("/signed") == 1);

        String header = signatures.get("/signed").getFirst();
        String body = bodies.get("/signed").getFirst();

        // Verified the way a merchant would: recompute over the bytes received, using the
        // secret they were given, and enforce the tolerance window.
        assertThat(header).startsWith("t=").contains(",v1=");
        assertThat(signer.verify(body, header, secret, Instant.now(), 300)).isTrue();
        // ...and a wrong secret must not verify, or the assertion above proves nothing.
        assertThat(signer.verify(body, header, "whsec_wrong", Instant.now(), 300)).isFalse();
        assertThat(endpoint.getId()).isNotNull();
    }

    @Test
    void thePayloadDeliveredIsTheCanonicalEventNotTheInternalEnvelope() throws Exception {
        UUID merchantId = UUID.randomUUID();
        registerEndpoint(merchantId, "test", "/canonical", "*");

        UUID eventId = UUID.randomUUID();
        publish(eventId, "PaymentAuthorized", UUID.randomUUID(), merchantId, "AUTHORIZED", "CREATED", null, "test");
        await().atMost(Duration.ofSeconds(20)).until(() -> hitsOn("/canonical") == 1);

        var body = objectMapper.readTree(bodies.get("/canonical").getFirst());
        assertThat(body.get("id").asString()).isEqualTo("evt_" + eventId.toString().replace("-", ""));
        assertThat(body.get("object").asString()).isEqualTo("event");
        assertThat(body.get("type").asString()).isEqualTo("payment.authorized");
        assertThat(body.get("mode").asString()).isEqualTo("test");
        assertThat(body.get("data").get("object").get("object").asString()).isEqualTo("payment");
        // The internal routing fields must never reach a merchant endpoint.
        assertThat(bodies.get("/canonical").getFirst()).doesNotContain("merchantContactEmail", "billing@acme.test");
    }

    @Test
    void aTestModeEventNeverReachesALiveEndpoint() throws Exception {
        UUID merchantId = UUID.randomUUID();
        registerEndpoint(merchantId, "test", "/mode-test", "*");
        registerEndpoint(merchantId, "live", "/mode-live", "*");

        publish(UUID.randomUUID(), "PaymentAuthorized", UUID.randomUUID(), merchantId, "AUTHORIZED", "CREATED",
                null, "test");
        await().atMost(Duration.ofSeconds(20)).until(() -> hitsOn("/mode-test") == 1);

        // M16's isolation guarantee, applied to the one subsystem that sends data outside
        // the platform — where a leak would be visible to a third party, not just to us.
        assertThat(hitsOn("/mode-live")).isZero();
    }

    @Test
    void aDisabledEndpointReceivesNothing() throws Exception {
        UUID merchantId = UUID.randomUUID();
        WebhookEndpoint disabled = registerEndpoint(merchantId, "test", "/disabled", "*");
        disabled.disable();
        endpointRepository.save(disabled);
        registerEndpoint(merchantId, "test", "/enabled", "*");

        publish(UUID.randomUUID(), "PaymentAuthorized", UUID.randomUUID(), merchantId, "AUTHORIZED", "CREATED",
                null, "test");
        await().atMost(Duration.ofSeconds(20)).until(() -> hitsOn("/enabled") == 1);

        assertThat(hitsOn("/disabled")).isZero();
    }

    @Test
    void everyAttemptIsRecordedWithItsFullRequestAndResponse() throws Exception {
        UUID merchantId = UUID.randomUUID();
        registerEndpoint(merchantId, "test", "/logged", "*");

        UUID eventId = UUID.randomUUID();
        publish(eventId, "PaymentAuthorized", UUID.randomUUID(), merchantId, "AUTHORIZED", "CREATED", null, "test");
        await().atMost(Duration.ofSeconds(20)).until(() -> hitsOn("/logged") == 1);

        WebhookDelivery delivery = deliveriesForEvent(eventId).getFirst();
        await().atMost(Duration.ofSeconds(10))
                .until(() -> !attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(delivery.getId()).isEmpty());

        WebhookDeliveryAttempt attempt =
                attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(delivery.getId()).getFirst();
        assertThat(attempt.getOutcome()).isEqualTo(AttemptOutcome.SUCCEEDED);
        assertThat(attempt.getAttemptNumber()).isEqualTo(1);
        assertThat(attempt.getResponseStatus()).isEqualTo(200);
        assertThat(attempt.getDurationMs()).isNotNull();
        assertThat(attempt.getRequestUrl()).endsWith("/logged");
        // The delivery log's whole purpose: showing the merchant exactly what was sent.
        assertThat(attempt.getRequestHeaders()).contains(WebhookSigner.SIGNATURE_HEADER);
        assertThat(attempt.getRequestBody()).contains("payment.authorized");
    }

    @Test
    void aLegacyWebhookUrlIsAdoptedIntoARealEndpointOnTheFirstEvent() throws Exception {
        UUID merchantId = UUID.randomUUID();
        // No endpoint registered: exactly the state of every V1 merchant at the cutover.
        publish(UUID.randomUUID(), "PaymentAuthorized", UUID.randomUUID(), merchantId, "AUTHORIZED", "CREATED",
                sinkUrl("/legacy"), "test");

        await().atMost(Duration.ofSeconds(20)).until(() -> hitsOn("/legacy") == 1);

        List<WebhookEndpoint> adopted =
                endpointRepository.findByMerchantIdAndModeOrderByCreatedAtAsc(merchantId, "test");
        assertThat(adopted).hasSize(1);
        assertThat(adopted.getFirst().isMigratedFromLegacy()).isTrue();
        // Wildcard, because V1 delivered every lifecycle event to the single URL.
        assertThat(subscriptionRepository.findByEndpointId(adopted.getFirst().getId()))
                .extracting(WebhookSubscription::getEventType)
                .containsExactly("*");
    }

    @Test
    void aMerchantWithRegisteredEndpointsIsNeverAugmentedByTheLegacyUrl() throws Exception {
        UUID merchantId = UUID.randomUUID();
        registerEndpoint(merchantId, "test", "/declared", "*");

        publish(UUID.randomUUID(), "PaymentAuthorized", UUID.randomUUID(), merchantId, "AUTHORIZED", "CREATED",
                sinkUrl("/legacy-should-not-be-used"), "test");
        await().atMost(Duration.ofSeconds(20)).until(() -> hitsOn("/declared") == 1);

        // Their endpoint list is their declared intent; the deprecated column must not
        // silently add a destination they never asked for.
        assertThat(hitsOn("/legacy-should-not-be-used")).isZero();
        assertThat(endpointRepository.findByMerchantIdAndModeOrderByCreatedAtAsc(merchantId, "test")).hasSize(1);
    }

    @Test
    void redeliveringTheSameEventDoesNotDuplicateAnything() throws Exception {
        UUID merchantId = UUID.randomUUID();
        registerEndpoint(merchantId, "test", "/dedup", "*");

        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        publish(eventId, "PaymentCaptured", paymentId, merchantId, "CAPTURED", "AUTHORIZED", null, "test");
        await().atMost(Duration.ofSeconds(20)).until(() -> hitsOn("/dedup") == 1);

        publish(eventId, "PaymentCaptured", paymentId, merchantId, "CAPTURED", "AUTHORIZED", null, "test");

        // Same key means same partition and strict ordering, so a *later* event arriving
        // proves the duplicate was already handled — deterministic, unlike a blind sleep.
        UUID followUp = UUID.randomUUID();
        publish(followUp, "PaymentRefunded", paymentId, merchantId, "REFUNDED", "CAPTURED", null, "test");
        await().atMost(Duration.ofSeconds(20)).until(() -> hitsOn("/dedup") == 2);

        assertThat(hitsOn("/dedup")).isEqualTo(2);
        assertThat(emailLogEntryRepository.findAll().stream()
                .filter(entry -> entry.getEventId().equals(eventId)).count()).isEqualTo(1);
        assertThat(deliveriesForEvent(eventId)).hasSize(1);
    }

    @Test
    void anInternalEventWithNoMerchantFacingCounterpartIsEmailedButNeverDelivered() throws Exception {
        UUID merchantId = UUID.randomUUID();
        registerEndpoint(merchantId, "test", "/not-for-this", "*");

        UUID eventId = UUID.randomUUID();
        publish(eventId, "ApiKeyIssued", UUID.randomUUID(), merchantId, "CREATED", null, null, "test");

        await().atMost(Duration.ofSeconds(20)).until(() -> emailLogEntryRepository.findAll().stream()
                .anyMatch(entry -> entry.getEventId().equals(eventId)));

        assertThat(webhookEventRepository.findBySourceEventId(eventId)).isEmpty();
        assertThat(hitsOn("/not-for-this")).isZero();
    }

    @Test
    void aMalformedMessageIsDroppedWithoutCrashingTheConsumer() throws Exception {
        producer.send(new ProducerRecord<>(TOPIC, "bad-key", "not valid json")).get(5, TimeUnit.SECONDS);

        UUID merchantId = UUID.randomUUID();
        registerEndpoint(merchantId, "test", "/after-malformed", "*");
        publish(UUID.randomUUID(), "PaymentRefunded", UUID.randomUUID(), merchantId, "REFUNDED", "CAPTURED",
                null, "test");

        await().atMost(Duration.ofSeconds(20)).until(() -> hitsOn("/after-malformed") == 1);
    }

    @Test
    void theModeIsRecordedOnTheEmailTheEventAndTheDelivery() throws Exception {
        UUID merchantId = UUID.randomUUID();
        registerEndpoint(merchantId, "live", "/live-mode", "*");

        UUID eventId = UUID.randomUUID();
        publish(eventId, "PaymentAuthorized", UUID.randomUUID(), merchantId, "AUTHORIZED", "CREATED", null, "live");
        await().atMost(Duration.ofSeconds(20)).until(() -> hitsOn("/live-mode") == 1);

        assertThat(webhookEventRepository.findBySourceEventId(eventId).orElseThrow().getMode()).isEqualTo("live");
        assertThat(deliveriesForEvent(eventId).getFirst().getMode()).isEqualTo("live");
        assertThat(emailLogEntryRepository.findAll().stream()
                .filter(entry -> entry.getEventId().equals(eventId)).findFirst().orElseThrow().getMode())
                .isEqualTo("live");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────

    private int hitsOn(String path) {
        return hits.getOrDefault(path, new AtomicInteger()).get();
    }

    private List<WebhookDelivery> deliveriesForEvent(UUID sourceEventId) {
        return webhookEventRepository.findBySourceEventId(sourceEventId)
                .map(event -> deliveryRepository.findAll().stream()
                        .filter(delivery -> event.getId().equals(delivery.getWebhookEventId()))
                        .toList())
                .orElse(List.of());
    }

    private void publish(UUID eventId, String eventType, UUID paymentId, UUID merchantId, String status,
                         String previousStatus, String legacyWebhookUrl, String mode) throws Exception {
        PaymentNotificationEventPayload payload = new PaymentNotificationEventPayload(
                paymentId, merchantId, 5000, "USD", status, previousStatus, 5000,
                "billing@acme.test", legacyWebhookUrl);
        EventEnvelope<PaymentNotificationEventPayload> envelope = new EventEnvelope<>(
                eventId, eventType, paymentId.toString(), Instant.now(), "test-correlation", mode, payload);
        producer.send(new ProducerRecord<>(TOPIC, paymentId.toString(),
                objectMapper.writeValueAsString(envelope))).get(5, TimeUnit.SECONDS);
    }
}
