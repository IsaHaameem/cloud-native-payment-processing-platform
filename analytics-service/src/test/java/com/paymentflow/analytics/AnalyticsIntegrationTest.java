package com.paymentflow.analytics;

import com.paymentflow.analytics.domain.MerchantPaymentStats;
import com.paymentflow.analytics.event.AnalyticsEventPayload;
import com.paymentflow.analytics.repository.MerchantPaymentStatsRepository;
import com.paymentflow.analytics.repository.ProcessedEventRepository;
import com.paymentflow.analytics.service.AnalyticsService;
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
import java.util.ArrayList;
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
 * Verifies the Kafka -> analytics-aggregate pipeline end-to-end against a real broker
 * and Postgres, mirroring transaction-service's identical {@code TransactionIntegrationTest}
 * (M6), including a concurrency test on one shared aggregate row.
 */
@SpringBootTest
@Testcontainers
class AnalyticsIntegrationTest {

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
    private MerchantPaymentStatsRepository merchantPaymentStatsRepository;
    @Autowired
    private ProcessedEventRepository processedEventRepository;
    @Autowired
    private AnalyticsService analyticsService;
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
    void fullLifecycleProducesCorrectAggregateCounts() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        publish(UUID.randomUUID(), "PaymentCreated", paymentId, merchantId, "CREATED", null, 10_000, "USD");
        publish(UUID.randomUUID(), "PaymentAuthorized", paymentId, merchantId, "AUTHORIZED", "CREATED", 10_000, "USD");
        publish(UUID.randomUUID(), "PaymentCaptured", paymentId, merchantId, "CAPTURED", "AUTHORIZED", 10_000, "USD");
        publish(UUID.randomUUID(), "PaymentPartiallyRefunded", paymentId, merchantId, "PARTIALLY_REFUNDED", "CAPTURED", 4_000, "USD");

        await().atMost(Duration.ofSeconds(15))
                .until(() -> statsFor(merchantId, "USD").map(s -> s.getRefundedCount() == 1).orElse(false));

        MerchantPaymentStats stats = statsFor(merchantId, "USD").orElseThrow();
        assertThat(stats.getCreatedCount()).isEqualTo(1);
        assertThat(stats.getAuthorizedCount()).isEqualTo(1);
        assertThat(stats.getCapturedCount()).isEqualTo(1);
        assertThat(stats.getTotalCapturedAmountMinor()).isEqualTo(10_000);
        assertThat(stats.getRefundedCount()).isEqualTo(1);
        assertThat(stats.getTotalRefundedAmountMinor()).isEqualTo(4_000);
    }

    @Test
    void redeliveringTheSameEventIsAnIdempotentNoOp() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        publish(eventId, "PaymentCreated", paymentId, merchantId, "CREATED", null, 5_000, "USD");
        await().atMost(Duration.ofSeconds(15))
                .until(() -> statsFor(merchantId, "USD").map(s -> s.getCreatedCount() == 1).orElse(false));

        publish(eventId, "PaymentCreated", paymentId, merchantId, "CREATED", null, 5_000, "USD");
        Thread.sleep(2000);

        assertThat(statsFor(merchantId, "USD").orElseThrow().getCreatedCount()).isEqualTo(1);
        assertThat(processedEventRepository.existsByEventId(eventId)).isTrue();
    }

    @Test
    void concurrentEventsForTheSameMerchantAndCurrencyRetryAndNeverLoseAnUpdate() throws Exception {
        UUID merchantId = UUID.randomUUID();
        String currency = "GBP";
        int concurrentEvents = 10;
        ExecutorService executor = Executors.newFixedThreadPool(concurrentEvents);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < concurrentEvents; i++) {
            UUID paymentId = UUID.randomUUID();
            futures.add(executor.submit(() -> analyticsService.processEvent(
                    envelope(UUID.randomUUID(), "PaymentCreated", paymentId, merchantId, "CREATED", null, 1_000, currency))));
        }
        for (var future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(statsFor(merchantId, currency).orElseThrow().getCreatedCount()).isEqualTo(concurrentEvents);
    }

    private java.util.Optional<MerchantPaymentStats> statsFor(UUID merchantId, String currency) {
        return merchantPaymentStatsRepository.findByMerchantIdAndCurrency(merchantId, currency);
    }

    private EventEnvelope<AnalyticsEventPayload> envelope(UUID eventId, String eventType, UUID paymentId,
                                                          UUID merchantId, String status, String previousStatus,
                                                          long eventAmountMinor, String currency) {
        AnalyticsEventPayload payload = new AnalyticsEventPayload(
                paymentId, merchantId, 10_000, currency, status, previousStatus, eventAmountMinor);
        return new EventEnvelope<>(eventId, eventType, paymentId.toString(), Instant.now(), "test-correlation", payload);
    }

    private void publish(UUID eventId, String eventType, UUID paymentId, UUID merchantId, String status,
                         String previousStatus, long eventAmountMinor, String currency) throws Exception {
        String json = objectMapper.writeValueAsString(
                envelope(eventId, eventType, paymentId, merchantId, status, previousStatus, eventAmountMinor, currency));
        producer.send(new ProducerRecord<>(TOPIC, paymentId.toString(), json)).get(5, TimeUnit.SECONDS);
    }
}
