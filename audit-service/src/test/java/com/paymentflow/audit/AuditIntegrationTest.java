package com.paymentflow.audit;

import com.paymentflow.audit.repository.AuditLogEntryRepository;
import com.paymentflow.common.dto.event.EventEnvelope;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Verifies the Kafka → audit-log pipeline end-to-end against a real broker and
 * Postgres, mirroring transaction-service's {@code TransactionIntegrationTest} (M6).
 */
@SpringBootTest
@Testcontainers
class AuditIntegrationTest {

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
    static void createTopic() throws Exception {
        Properties adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(adminProps)) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }
    }

    @Autowired
    private AuditLogEntryRepository auditLogEntryRepository;
    @Autowired
    private ObjectMapper objectMapper;

    private KafkaProducer<String, String> producer;

    @BeforeEach
    void setUpProducer() {
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

    private record TestPayload(UUID paymentId, String status) {
    }

    @Test
    void aRealPaymentEventIsRecordedVerbatim() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        publish(eventId, "PaymentAuthorized", paymentId, new TestPayload(paymentId, "AUTHORIZED"));

        await().atMost(Duration.ofSeconds(15)).until(() -> auditLogEntryRepository.existsByEventId(eventId));

        var entry = auditLogEntryRepository.findAll().stream()
                .filter(e -> e.getEventId().equals(eventId)).findFirst().orElseThrow();
        assertThat(entry.getEventType()).isEqualTo("PaymentAuthorized");
        assertThat(entry.getAggregateId()).isEqualTo(paymentId.toString());
        assertThat(entry.getCorrelationId()).isEqualTo("test-correlation");
        assertThat(objectMapper.readTree(entry.getPayload()).get("status").asString()).isEqualTo("AUTHORIZED");
    }

    @Test
    void redeliveringTheSameEventDoesNotDuplicateTheRow() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        publish(eventId, "PaymentCaptured", paymentId, new TestPayload(paymentId, "CAPTURED"));
        await().atMost(Duration.ofSeconds(15)).until(() -> auditLogEntryRepository.existsByEventId(eventId));

        publish(eventId, "PaymentCaptured", paymentId, new TestPayload(paymentId, "CAPTURED"));

        // Both publishes share paymentId as the Kafka message key, so the consumer
        // evaluates them strictly in send order (same partition). Awaiting this
        // follow-up event's own row is therefore proof the duplicate above was already
        // handled — deterministic, unlike a blind sleep-and-hope.
        UUID followUpEventId = UUID.randomUUID();
        publish(followUpEventId, "PaymentRefunded", paymentId, new TestPayload(paymentId, "REFUNDED"));
        await().atMost(Duration.ofSeconds(15)).until(() -> auditLogEntryRepository.existsByEventId(followUpEventId));

        long count = auditLogEntryRepository.findAll().stream()
                .filter(e -> e.getEventId().equals(eventId)).count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void aMalformedMessageIsDroppedWithoutCrashingTheConsumer() throws Exception {
        producer.send(new ProducerRecord<>(TOPIC, "bad-key", "not valid json")).get(5, TimeUnit.SECONDS);

        // A subsequent well-formed event still gets consumed and recorded — proof the
        // listener survived the malformed one rather than dying/blocking the partition.
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        publish(eventId, "PaymentVoided", paymentId, new TestPayload(paymentId, "VOIDED"));

        await().atMost(Duration.ofSeconds(15)).until(() -> auditLogEntryRepository.existsByEventId(eventId));
    }

    private void publish(UUID eventId, String eventType, UUID paymentId, TestPayload payload) throws Exception {
        EventEnvelope<TestPayload> envelope = new EventEnvelope<>(
                eventId, eventType, paymentId.toString(), Instant.now(), "test-correlation", payload);
        String json = objectMapper.writeValueAsString(envelope);
        producer.send(new ProducerRecord<>(TOPIC, paymentId.toString(), json)).get(5, TimeUnit.SECONDS);
    }

    /** Publishes an event whose envelope carries an explicit {@code mode} (M16); pass null for a mode-less event. */
    private void publishWithMode(String mode, UUID eventId, String eventType, UUID paymentId, TestPayload payload)
            throws Exception {
        EventEnvelope<TestPayload> envelope = new EventEnvelope<>(
                eventId, eventType, paymentId.toString(), Instant.now(), "test-correlation", mode, payload);
        producer.send(new ProducerRecord<>(TOPIC, paymentId.toString(),
                objectMapper.writeValueAsString(envelope))).get(5, TimeUnit.SECONDS);
    }

    @Test
    void theDeclaredModeIsRecordedVerbatimIncludingItsAbsence() throws Exception {
        // A test-mode event records mode "test".
        UUID testEventId = UUID.randomUUID();
        UUID testPaymentId = UUID.randomUUID();
        publishWithMode("test", testEventId, "PaymentCreated", testPaymentId, new TestPayload(testPaymentId, "CREATED"));

        // A live-mode event records mode "live".
        UUID liveEventId = UUID.randomUUID();
        UUID livePaymentId = UUID.randomUUID();
        publishWithMode("live", liveEventId, "PaymentCreated", livePaymentId, new TestPayload(livePaymentId, "CREATED"));

        // A mode-less event (as merchant.events are) records mode null — no coercion to live.
        UUID modelessEventId = UUID.randomUUID();
        UUID modelessPaymentId = UUID.randomUUID();
        publishWithMode(null, modelessEventId, "PaymentCreated", modelessPaymentId,
                new TestPayload(modelessPaymentId, "CREATED"));

        await().atMost(Duration.ofSeconds(15)).until(() ->
                auditLogEntryRepository.existsByEventId(testEventId)
                        && auditLogEntryRepository.existsByEventId(liveEventId)
                        && auditLogEntryRepository.existsByEventId(modelessEventId));

        assertThat(modeOf(testEventId)).isEqualTo("test");
        assertThat(modeOf(liveEventId)).isEqualTo("live");
        assertThat(modeOf(modelessEventId)).isNull();
    }

    private String modeOf(UUID eventId) {
        return auditLogEntryRepository.findAll().stream()
                .filter(e -> e.getEventId().equals(eventId)).findFirst().orElseThrow().getMode();
    }
}
