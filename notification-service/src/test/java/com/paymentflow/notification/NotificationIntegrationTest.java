package com.paymentflow.notification;

import com.paymentflow.common.dto.event.EventEnvelope;
import com.paymentflow.notification.domain.DeliveryStatus;
import com.paymentflow.notification.event.PaymentNotificationEventPayload;
import com.paymentflow.notification.repository.EmailLogEntryRepository;
import com.paymentflow.notification.repository.WebhookDeliveryRepository;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Verifies the Kafka -> notification pipeline end-to-end against a real broker and
 * Postgres, including real outbound HTTP delivery to a throwaway JDK {@link HttpServer}
 * standing in for a merchant's webhook endpoint — mirrors transaction-service's/
 * audit-service's identical Kafka-integration-test shape (M6/M7).
 */
@SpringBootTest
@Testcontainers
class NotificationIntegrationTest {

    private static final String TOPIC = "payment.events";
    private static final String RETRY_TOPIC = "payment.events.retry";
    private static final String DLQ_TOPIC = "payment.events.dlq";

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
                    new NewTopic(RETRY_TOPIC, 3, (short) 1),
                    new NewTopic(DLQ_TOPIC, 3, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }
    }

    private static HttpServer webhookSink;
    private static volatile int webhookResponseStatus = 200;
    private static final AtomicInteger webhookCallCount = new AtomicInteger();

    @BeforeAll
    static void startWebhookSink() throws Exception {
        webhookSink = HttpServer.create(new InetSocketAddress(0), 0);
        webhookSink.createContext("/hook", exchange -> {
            webhookCallCount.incrementAndGet();
            exchange.sendResponseHeaders(webhookResponseStatus, -1);
            exchange.close();
        });
        webhookSink.start();
    }

    @AfterAll
    static void stopWebhookSink() {
        if (webhookSink != null) {
            webhookSink.stop(0);
        }
    }

    private String webhookUrl() {
        return "http://localhost:" + webhookSink.getAddress().getPort() + "/hook";
    }

    @Autowired
    private EmailLogEntryRepository emailLogEntryRepository;
    @Autowired
    private WebhookDeliveryRepository webhookDeliveryRepository;
    @Autowired
    private ObjectMapper objectMapper;

    private KafkaProducer<String, String> producer;

    @BeforeEach
    void setUpProducer() {
        webhookResponseStatus = 200;
        webhookCallCount.set(0);
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
    void anEventWithNoWebhookConfiguredOnlyLogsAnEmail() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        publish(eventId, "PaymentCreated", paymentId, "CREATED", null, null);

        await().atMost(Duration.ofSeconds(15)).until(() -> emailLogEntryRepository.findAll().stream()
                .anyMatch(e -> e.getEventId().equals(eventId)));

        assertThat(webhookDeliveryRepository.findByEventId(eventId)).isEmpty();
    }

    @Test
    void aConfiguredWebhookIsDeliveredOnTheFirstAttempt() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        publish(eventId, "PaymentAuthorized", paymentId, "AUTHORIZED", "CREATED", webhookUrl());

        await().atMost(Duration.ofSeconds(15)).until(() -> webhookDeliveryRepository.findByEventId(eventId)
                .map(d -> d.getStatus() == DeliveryStatus.DELIVERED).orElse(false));

        assertThat(webhookCallCount.get()).isEqualTo(1);
        assertThat(emailLogEntryRepository.findAll()).anyMatch(e -> e.getEventId().equals(eventId));
    }

    @Test
    void redeliveringTheSameEventDoesNotDuplicateTheEmailOrWebhookRow() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        publish(eventId, "PaymentCaptured", paymentId, "CAPTURED", "AUTHORIZED", webhookUrl());

        await().atMost(Duration.ofSeconds(15)).until(() -> webhookDeliveryRepository.findByEventId(eventId)
                .map(d -> d.getStatus() == DeliveryStatus.DELIVERED).orElse(false));
        int callsAfterFirst = webhookCallCount.get();

        publish(eventId, "PaymentCaptured", paymentId, "CAPTURED", "AUTHORIZED", webhookUrl());
        Thread.sleep(2000);

        assertThat(webhookCallCount.get()).isEqualTo(callsAfterFirst);
        assertThat(emailLogEntryRepository.findAll().stream().filter(e -> e.getEventId().equals(eventId)).count())
                .isEqualTo(1);
    }

    @Test
    void aFailingWebhookRetriesThenEventuallyDelivers() throws Exception {
        webhookResponseStatus = 503;
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        publish(eventId, "PaymentVoided", paymentId, "VOIDED", "AUTHORIZED", webhookUrl());

        // First (inline) attempt fails and lands on the retry topic.
        await().atMost(Duration.ofSeconds(15)).until(() -> webhookDeliveryRepository.findByEventId(eventId)
                .map(d -> d.getAttemptCount() >= 1).orElse(false));

        // Once the sink starts accepting requests, a subsequent retry succeeds.
        webhookResponseStatus = 200;
        await().atMost(Duration.ofSeconds(30)).until(() -> webhookDeliveryRepository.findByEventId(eventId)
                .map(d -> d.getStatus() == DeliveryStatus.DELIVERED).orElse(false));
    }

    @Test
    void aMalformedMessageIsDroppedWithoutCrashingTheConsumer() throws Exception {
        producer.send(new ProducerRecord<>(TOPIC, "bad-key", "not valid json")).get(5, TimeUnit.SECONDS);

        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        publish(eventId, "PaymentRefunded", paymentId, "REFUNDED", "CAPTURED", null);

        await().atMost(Duration.ofSeconds(15)).until(() -> emailLogEntryRepository.findAll().stream()
                .anyMatch(e -> e.getEventId().equals(eventId)));
    }

    private void publish(UUID eventId, String eventType, UUID paymentId, String status, String previousStatus,
                         String webhookUrl) throws Exception {
        PaymentNotificationEventPayload payload = new PaymentNotificationEventPayload(
                paymentId, UUID.randomUUID(), 5000, "USD", status, previousStatus, 5000,
                "billing@acme.test", webhookUrl);
        EventEnvelope<PaymentNotificationEventPayload> envelope = new EventEnvelope<>(
                eventId, eventType, paymentId.toString(), Instant.now(), "test-correlation", payload);
        String json = objectMapper.writeValueAsString(envelope);
        producer.send(new ProducerRecord<>(TOPIC, paymentId.toString(), json)).get(5, TimeUnit.SECONDS);
    }
}
