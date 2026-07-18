package com.paymentflow.payment.outbox;

import com.paymentflow.payment.domain.OutboxEvent;
import com.paymentflow.payment.event.PaymentEventPublisher;
import com.paymentflow.payment.repository.OutboxEventRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the outbox → Kafka pipeline end-to-end against a real broker: seeded
 * unpublished rows get published and marked, and an already-published row is never
 * re-sent. {@link PaymentIntegrationTest} covers the HTTP/FSM/idempotency surface
 * without needing a broker at all — split out so most of the suite runs Kafka-free.
 *
 * <p>The background {@code @Scheduled} relay tick is pushed out to effectively never
 * fire here — this test drives {@link OutboxRelay#relay()} explicitly for determinism.
 * Left running, it would race the explicit calls (both could see the same
 * unpublished row before either's commit lands) and double-publish — a real, accepted
 * at-least-once outcome in production (D2, consumers dedupe on {@code eventId}), but
 * one this test isn't trying to exercise.
 */
@SpringBootTest(properties = "paymentflow.outbox.relay-interval-ms=999999999")
@Testcontainers
class OutboxRelayIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    // Confluent's image is used here only because Testcontainers' KafkaContainer
    // wait-strategy doesn't match apache/kafka:3.9.0's log output out of the box; the
    // actual local/dev stack (docker-compose.infra.yml) still runs apache/kafka (D9) —
    // this only affects this test's throwaway broker.
    @Container
    static ConfluentKafkaContainer kafka =
            new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    @org.springframework.test.context.DynamicPropertySource
    static void registerKafkaBootstrapServers(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private OutboxEventRepository outboxEventRepository;
    @Autowired
    private OutboxRelay outboxRelay;

    private KafkaConsumer<String, String> consumer;

    @BeforeEach
    void subscribeConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(PaymentEventPublisher.TOPIC));
    }

    @AfterEach
    void closeConsumer() {
        consumer.close();
    }

    @Test
    void relayPublishesUnpublishedRowsAndMarksThemPublished() {
        UUID aggregateId = UUID.randomUUID();
        OutboxEvent event = outboxEventRepository.save(
                OutboxEvent.of(aggregateId, "PaymentCreated", PaymentEventPublisher.TOPIC,
                        "{\"eventType\":\"PaymentCreated\",\"aggregateId\":\"" + aggregateId + "\"}"));
        assertThat(event.isPublished()).isFalse();

        outboxRelay.relay();

        // Each test's consumer group starts from "earliest", so the topic may already
        // carry other tests' (or @EnableScheduling's own background relay tick's)
        // messages — filter to this test's own aggregate id rather than asserting a
        // raw count over the whole topic.
        List<ConsumerRecord<String, String>> matching = pollRecordsMatchingKey(aggregateId.toString(), true);
        assertThat(matching).hasSize(1);
        assertThat(matching.get(0).value()).contains("PaymentCreated").contains(aggregateId.toString());

        OutboxEvent reloaded = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(reloaded.isPublished()).isTrue();
    }

    @Test
    void relayDoesNotRepublishAnAlreadyPublishedRow() {
        UUID aggregateId = UUID.randomUUID();
        outboxEventRepository.save(OutboxEvent.of(aggregateId, "PaymentAuthorized", PaymentEventPublisher.TOPIC,
                "{\"eventType\":\"PaymentAuthorized\"}"));

        outboxRelay.relay();
        assertThat(pollRecordsMatchingKey(aggregateId.toString(), true)).hasSize(1);

        outboxRelay.relay(); // already published — this row should not be sent again
        // The consumer already consumed that one message above; a second poll only
        // ever sees a *new* one. None should arrive, proving no re-publish happened.
        assertThat(pollRecordsMatchingKey(aggregateId.toString(), false)).isEmpty();
    }

    /**
     * Polls for records with the given key. When {@code expectAtLeastOne} is true,
     * polls until one arrives (or a timeout elapses); when false, polls for a fixed
     * short window and returns whatever (if anything) showed up — used to assert
     * *absence* without waiting out the full timeout every time.
     */
    private List<ConsumerRecord<String, String>> pollRecordsMatchingKey(String key, boolean expectAtLeastOne) {
        List<ConsumerRecord<String, String>> matching = new java.util.ArrayList<>();
        long deadline = System.currentTimeMillis() + (expectAtLeastOne ? 10_000 : 3_000);
        while ((expectAtLeastOne ? matching.isEmpty() : true) && System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> batch = consumer.poll(Duration.ofMillis(500));
            batch.forEach(record -> {
                if (record.key().equals(key)) {
                    matching.add(record);
                }
            });
        }
        return matching;
    }
}
