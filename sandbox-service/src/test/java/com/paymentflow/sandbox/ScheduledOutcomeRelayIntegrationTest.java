package com.paymentflow.sandbox;

import com.paymentflow.sandbox.domain.DecisionOutcome;
import com.paymentflow.sandbox.domain.Operation;
import com.paymentflow.sandbox.domain.ScheduledOutcome;
import com.paymentflow.sandbox.repository.ScheduledOutcomeRepository;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Verifies the deferred-outcome relay end-to-end against a real broker and Postgres
 * (M17.6): a due, undelivered {@code scheduled_outcomes} row is published to {@code
 * sandbox.scheduled.events} and marked delivered. Mirrors payment-service's own outbox
 * relay test pattern — seed the row directly (the scheduling side is
 * {@code SandboxDecisionIntegrationTest}'s concern), assert the relay's own polling and
 * publishing behavior in isolation.
 */
@SpringBootTest
@Testcontainers
class ScheduledOutcomeRelayIntegrationTest {

    private static final String TOPIC = "sandbox.scheduled.events";

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
        // Fast enough that the tests below don't need a long await budget, comfortably
        // above zero so the relay's own polling loop is still exercised, not bypassed.
        registry.add("paymentflow.scheduled-outcomes.relay-interval-ms", () -> "300");
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
    private ScheduledOutcomeRepository scheduledOutcomeRepository;

    private KafkaConsumer<String, String> consumer;

    @BeforeEach
    void setUpConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(TOPIC));
    }

    @AfterEach
    void closeConsumer() {
        consumer.close();
    }

    @Test
    void aDueRowIsPublishedAndMarkedDelivered() {
        UUID paymentId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        ScheduledOutcome outcome = scheduledOutcomeRepository.save(ScheduledOutcome.create(
                paymentId, merchantId, "test", Operation.CAPTURE, DecisionOutcome.APPROVE,
                Instant.now().minusSeconds(1))); // already due

        ConsumerRecord<String, String> record = pollUntilOneRecord();
        assertThat(record.key()).isEqualTo(paymentId.toString());
        assertThat(record.value()).contains("\"paymentId\":\"" + paymentId + "\"");
        assertThat(record.value()).contains("\"operation\":\"CAPTURE\"");
        assertThat(record.value()).contains("\"outcome\":\"APPROVE\"");
        assertThat(record.value()).contains("\"mode\":\"test\"");

        await().atMost(Duration.ofSeconds(10))
                .until(() -> scheduledOutcomeRepository.findById(outcome.getId()).orElseThrow().isDelivered());
    }

    @Test
    void aNotYetDueRowIsNotPublished() throws Exception {
        UUID paymentId = UUID.randomUUID();
        scheduledOutcomeRepository.save(ScheduledOutcome.create(
                paymentId, UUID.randomUUID(), "test", Operation.CAPTURE, DecisionOutcome.APPROVE,
                Instant.now().plusSeconds(30))); // far in the future

        // A fresh consumer group with auto-offset-reset=earliest also sees whatever an
        // earlier test method already published to this shared topic — filtering by
        // this test's own paymentId (the message key) is what actually proves nothing
        // was published for *this* row, not that the topic is empty.
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(2));
        boolean publishedForThisPayment = false;
        for (ConsumerRecord<String, String> record : records) {
            if (paymentId.toString().equals(record.key())) {
                publishedForThisPayment = true;
            }
        }
        assertThat(publishedForThisPayment).isFalse();
    }

    private ConsumerRecord<String, String> pollUntilOneRecord() {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            if (!records.isEmpty()) {
                return records.iterator().next();
            }
        }
        throw new AssertionError("No record published to " + TOPIC + " within the wait budget");
    }
}
