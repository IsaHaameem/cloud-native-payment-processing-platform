package com.paymentflow.notification;

import com.paymentflow.common.dto.event.EventEnvelope;
import com.paymentflow.notification.crypto.WebhookSecretCipher;
import com.paymentflow.notification.domain.AttemptOutcome;
import com.paymentflow.notification.domain.DeliveryStatus;
import com.paymentflow.notification.domain.EndpointDisableReason;
import com.paymentflow.notification.domain.WebhookDelivery;
import com.paymentflow.notification.domain.WebhookEndpoint;
import com.paymentflow.notification.domain.WebhookSubscription;
import com.paymentflow.notification.event.PaymentNotificationEventPayload;
import com.paymentflow.notification.repository.EmailLogEntryRepository;
import com.paymentflow.notification.repository.WebhookDeliveryAttemptRepository;
import com.paymentflow.notification.repository.WebhookDeliveryRepository;
import com.paymentflow.notification.repository.WebhookEndpointRepository;
import com.paymentflow.notification.repository.WebhookEventRepository;
import com.paymentflow.notification.repository.WebhookSubscriptionRepository;
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
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The retry schedule, dead-lettering, and auto-disable against a real broker, real
 * Postgres, and a real endpoint that refuses every delivery (M18.7).
 *
 * <p>Separate from {@code NotificationIntegrationTest} because it needs a deliberately
 * hostile schedule configuration — a handful of one-second retries and a failure
 * threshold of three — that would make every other test in that suite slower and less
 * clear. The schedule's *arithmetic* is proven in {@code WebhookRetryScheduleTest}; what
 * this proves is that the machinery around it actually walks the schedule, gives up at
 * the right point, and switches the endpoint off.
 */
@SpringBootTest(properties = {
        "paymentflow.webhooks.require-https=false",
        "paymentflow.webhooks.allowed-hosts=localhost",
        "paymentflow.webhooks.retry-schedule=1s,1s",
        "paymentflow.webhooks.retry-relay-interval-ms=250",
        "paymentflow.webhooks.auto-disable-after-consecutive-failures=3"
})
@Testcontainers
class WebhookRetryAndAutoDisableIntegrationTest {

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

    private static HttpServer sink;
    private static final AtomicInteger deadSinkHits = new AtomicInteger();

    @BeforeAll
    static void startSink() throws Exception {
        sink = HttpServer.create(new InetSocketAddress(0), 0);
        // An endpoint that has been broken for a week — the case auto-disable exists for.
        sink.createContext("/always-500", exchange -> {
            deadSinkHits.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
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
    private EmailLogEntryRepository emailLogEntryRepository;
    @Autowired
    private WebhookSecretCipher secretCipher;
    @Autowired
    private ObjectMapper objectMapper;

    private KafkaProducer<String, String> producer;

    @BeforeEach
    void setUp() {
        deadSinkHits.set(0);
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

    @Test
    void aDeadEndpointWalksTheWholeScheduleThenDeadLetters() throws Exception {
        UUID merchantId = UUID.randomUUID();
        registerDeadEndpoint(merchantId);

        UUID eventId = UUID.randomUUID();
        publish(eventId, merchantId);

        // 1 immediate attempt + 2 scheduled retries = 3, then the schedule is exhausted.
        await().atMost(Duration.ofSeconds(45)).until(() -> deliveryFor(eventId)
                .map(delivery -> delivery.getStatus() == DeliveryStatus.DEAD_LETTERED).orElse(false));

        WebhookDelivery delivery = deliveryFor(eventId).orElseThrow();
        assertThat(delivery.getAttemptCount()).isEqualTo(3);
        // No further attempt is scheduled once it has given up — a lingering
        // next_attempt_at would have the relay re-dispatching it forever.
        assertThat(delivery.getNextAttemptAt()).isNull();

        List<com.paymentflow.notification.domain.WebhookDeliveryAttempt> attempts =
                attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(delivery.getId());
        assertThat(attempts).hasSize(3);
        assertThat(attempts).allMatch(attempt -> attempt.getOutcome() == AttemptOutcome.FAILED_STATUS);
        assertThat(attempts).allMatch(attempt -> attempt.getResponseStatus() == 500);
        // Numbered in order, with no gaps — the delivery log is meant to be readable.
        assertThat(attempts).extracting(
                com.paymentflow.notification.domain.WebhookDeliveryAttempt::getAttemptNumber)
                .containsExactly(1, 2, 3);
    }

    @Test
    void anEndpointFailingRepeatedlyIsAutoDisabledAndTheMerchantIsNotified() throws Exception {
        UUID merchantId = UUID.randomUUID();
        WebhookEndpoint endpoint = registerDeadEndpoint(merchantId);

        // Threshold is 3 consecutive failures; one event yields 3 attempts.
        publish(UUID.randomUUID(), merchantId);

        await().atMost(Duration.ofSeconds(45)).until(() ->
                endpointRepository.findById(endpoint.getId()).orElseThrow().getDisabledAt() != null);

        WebhookEndpoint disabled = endpointRepository.findById(endpoint.getId()).orElseThrow();
        assertThat(disabled.isEnabled()).isFalse();
        assertThat(disabled.getDisabledReason()).isEqualTo(EndpointDisableReason.CONSECUTIVE_FAILURES);
        assertThat(disabled.getConsecutiveFailureCount()).isGreaterThanOrEqualTo(3);

        // The merchant must not discover this from missing traffic.
        await().atMost(Duration.ofSeconds(10)).until(() -> emailLogEntryRepository.findAll().stream()
                .anyMatch(entry -> "Your webhook endpoint has been disabled".equals(entry.getSubject())));
    }

    @Test
    void aDisabledEndpointStopsConsumingTheRetryBudget() throws Exception {
        UUID merchantId = UUID.randomUUID();
        WebhookEndpoint endpoint = registerDeadEndpoint(merchantId);

        publish(UUID.randomUUID(), merchantId);
        await().atMost(Duration.ofSeconds(45)).until(() ->
                endpointRepository.findById(endpoint.getId()).orElseThrow().getDisabledAt() != null);
        int hitsAtDisable = deadSinkHits.get();

        // A second event after the endpoint was switched off must not be delivered at all
        // — that is the entire point of auto-disable (§4.5: "this protects the platform
        // from spending its retry budget on an endpoint that has been dead for a week").
        publish(UUID.randomUUID(), merchantId);
        Thread.sleep(3000);

        assertThat(deadSinkHits.get()).isEqualTo(hitsAtDisable);
    }

    @Test
    void aSuccessfulDeliveryResetsTheStreakSoAFlakyEndpointIsNotDisabled() throws Exception {
        UUID merchantId = UUID.randomUUID();
        WebhookEndpoint endpoint = endpointRepository.save(WebhookEndpoint.register(merchantId, "test",
                "http://localhost:" + sink.getAddress().getPort() + "/always-500", "flaky",
                TestWebhookProperties.API_VERSION, secretCipher.encrypt("whsec_x"), "whsec_x", "ops@merchant.test"));
        subscriptionRepository.save(WebhookSubscription.of(endpoint.getId(), "*"));

        // Two failures, then the endpoint recovers before the third.
        endpoint.recordDeliveryFailure();
        endpoint.recordDeliveryFailure();
        endpointRepository.save(endpoint);
        assertThat(endpointRepository.findById(endpoint.getId()).orElseThrow()
                .getConsecutiveFailureCount()).isEqualTo(2);

        WebhookEndpoint recovered = endpointRepository.findById(endpoint.getId()).orElseThrow();
        recovered.recordDeliverySuccess();
        endpointRepository.save(recovered);

        // Without the reset, a merely flaky endpoint would accumulate toward the threshold
        // across weeks and eventually be disabled as though it were dead.
        assertThat(endpointRepository.findById(endpoint.getId()).orElseThrow()
                .getConsecutiveFailureCount()).isZero();
        assertThat(endpointRepository.findById(endpoint.getId()).orElseThrow().isEnabled()).isTrue();
    }

    private WebhookEndpoint registerDeadEndpoint(UUID merchantId) {
        String rawSecret = "whsec_" + UUID.randomUUID().toString().replace("-", "");
        WebhookEndpoint endpoint = endpointRepository.save(WebhookEndpoint.register(merchantId, "test",
                "http://localhost:" + sink.getAddress().getPort() + "/always-500", "dead endpoint",
                TestWebhookProperties.API_VERSION, secretCipher.encrypt(rawSecret), rawSecret.substring(0, 12),
                "ops@merchant.test"));
        subscriptionRepository.save(WebhookSubscription.of(endpoint.getId(), "*"));
        return endpoint;
    }

    private java.util.Optional<WebhookDelivery> deliveryFor(UUID sourceEventId) {
        return webhookEventRepository.findBySourceEventId(sourceEventId)
                .flatMap(event -> deliveryRepository.findByWebhookEventIdOrderByCreatedAtAsc(event.getId())
                        .stream().findFirst());
    }

    private void publish(UUID eventId, UUID merchantId) throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentNotificationEventPayload payload = new PaymentNotificationEventPayload(
                paymentId, merchantId, 5000, "USD", "AUTHORIZED", "CREATED", 5000, "billing@acme.test", null);
        EventEnvelope<PaymentNotificationEventPayload> envelope = new EventEnvelope<>(
                eventId, "PaymentAuthorized", paymentId.toString(), Instant.now(), "corr", "test", payload);
        producer.send(new ProducerRecord<>(TOPIC, paymentId.toString(),
                objectMapper.writeValueAsString(envelope))).get(5, TimeUnit.SECONDS);
    }
}
