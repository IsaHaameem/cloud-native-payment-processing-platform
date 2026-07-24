package com.paymentflow.payment;

import com.paymentflow.common.dto.event.EventEnvelope;
import com.paymentflow.payment.authorization.sandbox.SandboxScheduledOutcomePayload;
import com.paymentflow.payment.domain.OutboxEvent;
import com.paymentflow.payment.domain.Payment;
import com.paymentflow.payment.domain.PaymentStatus;
import com.paymentflow.payment.event.PaymentEventPublisher;
import com.paymentflow.payment.merchant.MerchantSummary;
import com.paymentflow.payment.repository.OutboxEventRepository;
import com.paymentflow.payment.repository.PaymentRepository;
import com.paymentflow.payment.repository.ProcessedEventRepository;
import com.paymentflow.payment.service.PaymentService;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Verifies payment-service's first Kafka consumer role (M17.6) end-to-end against a
 * real broker and Postgres: publishes real {@code sandbox.scheduled.events} messages
 * (the same envelope/payload shape sandbox-service emits) and asserts the resulting
 * FSM transition and outbox event. Mirrors transaction-service's own
 * {@code TransactionIntegrationTest} pattern (M6) exactly — publish real Kafka
 * messages, poll for the async effect, no live producer service required to test this
 * service's own consumer in isolation.
 */
@SpringBootTest
@Testcontainers
class SandboxScheduledEventIntegrationTest {

    private static final String TOPIC = "sandbox.scheduled.events";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    // Confluent's image only because Testcontainers' plain KafkaContainer wait-strategy
    // doesn't match apache/kafka:3.9.0's log output out of the box (same call as M6's
    // TransactionIntegrationTest) — the real dev/prod stack still runs apache/kafka (D9).
    @Container
    static ConfluentKafkaContainer kafka =
            new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    /** Creates the topic explicitly before the Spring context's @KafkaListener starts (same rationale as M6). */
    @BeforeAll
    static void createTopic() throws Exception {
        Properties adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(adminProps)) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }
    }

    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private OutboxEventRepository outboxEventRepository;
    @Autowired
    private ProcessedEventRepository processedEventRepository;
    @Autowired
    private PaymentEventPublisher eventPublisher;
    @Autowired
    private PaymentService paymentService;
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

    @Test
    void deferredCaptureTransitionsAnAuthorizedPaymentToCaptured() throws Exception {
        UUID paymentId = seedAuthorizedPayment("test");

        publish(UUID.randomUUID(), "DeferredOutcomeSettled", paymentId, "test", "CAPTURE");

        await().atMost(Duration.ofSeconds(15)).until(() -> reload(paymentId).getStatus() == PaymentStatus.CAPTURED);
        Payment captured = reload(paymentId);
        assertThat(captured.getCapturedAmountMinor()).isEqualTo(5000);

        List<OutboxEvent> events = outboxEventRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(paymentId) && e.getEventType().equals("PaymentCaptured"))
                .toList();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getPayload()).contains("\"merchantContactEmail\":\"merchant@example.test\"");
    }

    @Test
    void redeliveringTheSameEventIsAnIdempotentNoOp() throws Exception {
        UUID paymentId = seedAuthorizedPayment("test");
        UUID eventId = UUID.randomUUID();

        publish(eventId, "DeferredOutcomeSettled", paymentId, "test", "CAPTURE");
        await().atMost(Duration.ofSeconds(15)).until(() -> reload(paymentId).getStatus() == PaymentStatus.CAPTURED);

        publish(eventId, "DeferredOutcomeSettled", paymentId, "test", "CAPTURE");
        // No second transition is possible to await on — assert the durable state
        // directly after giving the (synchronous, single-partition-key-ordered) second
        // delivery time to have been handled.
        await().atMost(Duration.ofSeconds(10)).until(() -> processedEventRepository.existsByEventId(eventId));

        long capturedEvents = outboxEventRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(paymentId) && e.getEventType().equals("PaymentCaptured"))
                .count();
        assertThat(capturedEvents).isEqualTo(1);
    }

    @Test
    void deferredCaptureOnAnAlreadyCapturedPaymentIsANoOp() throws Exception {
        UUID paymentId = seedAuthorizedPayment("test");
        // Captured directly (not via the deferred path) before the deferred event ever
        // arrives — simulates a client's own explicit POST /capture beating the
        // scheduled settlement, the real race this milestone's FSM guard must survive.
        Payment payment = reload(paymentId);
        payment.capture();
        paymentRepository.save(payment);

        UUID eventId = UUID.randomUUID();
        publish(eventId, "DeferredOutcomeSettled", paymentId, "test", "CAPTURE");
        await().atMost(Duration.ofSeconds(10)).until(() -> processedEventRepository.existsByEventId(eventId));

        long capturedEvents = outboxEventRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(paymentId) && e.getEventType().equals("PaymentCaptured"))
                .count();
        assertThat(capturedEvents).isZero(); // seeded directly, never through eventPublisher
        assertThat(reload(paymentId).getCapturedAmountMinor()).isEqualTo(5000);
    }

    @Test
    void aClientCaptureRacingTheDeferredEventNeverDoubleCaptures() throws Exception {
        UUID paymentId = seedAuthorizedPayment("test");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> deferred = executor.submit(
                    () -> paymentService.applyDeferredCapture(UUID.randomUUID(), "DeferredOutcomeSettled", paymentId, "test"));
            Future<?> direct = executor.submit(() -> {
                Payment payment = reload(paymentId);
                try {
                    payment.capture();
                    paymentRepository.save(payment);
                } catch (RuntimeException ignoredLostRace) {
                    // Whichever side loses the FSM race throwing is the expected, safe outcome.
                }
            });
            deferred.get(15, TimeUnit.SECONDS);
            direct.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
        }

        assertThat(reload(paymentId).getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(reload(paymentId).getCapturedAmountMinor()).isEqualTo(5000);
    }

    private Payment reload(UUID paymentId) {
        return paymentRepository.findById(paymentId).orElseThrow();
    }

    private UUID seedAuthorizedPayment(String mode) {
        Payment payment = Payment.create(UUID.randomUUID(), mode, 5000, "USD", null, "pm_card_delayedSettlement");
        payment = paymentRepository.save(payment);
        payment.authorize();
        payment = paymentRepository.save(payment);
        MerchantSummary merchant = new MerchantSummary(payment.getMerchantId(), "merchant@example.test",
                "https://merchant.example.test/webhooks");
        eventPublisher.publish(payment, "PaymentAuthorized", PaymentStatus.CREATED, payment.getAmountMinor(), merchant);
        return payment.getId();
    }

    private void publish(UUID eventId, String eventType, UUID paymentId, String mode, String operation)
            throws Exception {
        SandboxScheduledOutcomePayload payload = new SandboxScheduledOutcomePayload(paymentId, operation, "APPROVE");
        EventEnvelope<SandboxScheduledOutcomePayload> envelope = new EventEnvelope<>(
                eventId, eventType, paymentId.toString(), Instant.now(), "test-correlation", mode, payload);
        String json = objectMapper.writeValueAsString(envelope);
        producer.send(new ProducerRecord<>(TOPIC, paymentId.toString(), json)).get(5, TimeUnit.SECONDS);
    }
}
