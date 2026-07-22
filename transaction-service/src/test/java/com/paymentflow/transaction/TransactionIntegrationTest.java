package com.paymentflow.transaction;

import com.paymentflow.common.dto.event.EventEnvelope;
import com.paymentflow.transaction.domain.Account;
import com.paymentflow.transaction.domain.AccountType;
import com.paymentflow.transaction.domain.LedgerTransaction;
import com.paymentflow.transaction.event.PaymentLedgerEventPayload;
import com.paymentflow.transaction.repository.AccountRepository;
import com.paymentflow.transaction.repository.LedgerEntryRepository;
import com.paymentflow.transaction.repository.LedgerTransactionRepository;
import com.paymentflow.transaction.repository.ProcessedEventRepository;
import com.paymentflow.transaction.service.LedgerService;
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
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Verifies the Kafka → ledger pipeline end-to-end against a real broker and Postgres:
 * publishes real {@code payment.events} messages (the same envelope/payload shape
 * payment-service emits) and asserts the resulting ledger entries and account
 * balances. Consumption is async, so assertions poll with a timeout rather than
 * checking immediately after publish.
 */
@SpringBootTest
@Testcontainers
class TransactionIntegrationTest {

    private static final String TOPIC = "payment.events";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    // Confluent's image only because Testcontainers' plain KafkaContainer wait-strategy
    // doesn't match apache/kafka:3.9.0's log output out of the box (same call as M5's
    // OutboxRelayIntegrationTest) — the real dev/prod stack still runs apache/kafka (D9).
    @Container
    static ConfluentKafkaContainer kafka =
            new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    /**
     * Creates the topic explicitly (matching production — auto-create is disabled on
     * the real broker, D10) before the Spring context (and its {@code @KafkaListener})
     * starts. Relying on lazy auto-create on first produce risked the consumer's
     * subscription and the topic's actual creation racing, with no guarantee the
     * listener would notice a topic that didn't exist yet when it subscribed.
     */
    @BeforeAll
    static void createTopic() throws Exception {
        Properties adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(adminProps)) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }
    }

    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private LedgerTransactionRepository ledgerTransactionRepository;
    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;
    @Autowired
    private ProcessedEventRepository processedEventRepository;
    @Autowired
    private LedgerService ledgerService;
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
    void fullLifecyclePostsCorrectLedgerEntriesAndNetsToZeroOnceFullyRefunded() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();

        publish(UUID.randomUUID(), "PaymentAuthorized", paymentId, merchantId, "AUTHORIZED", "CREATED", 10_000);
        publish(UUID.randomUUID(), "PaymentCaptured", paymentId, merchantId, "CAPTURED", "AUTHORIZED", 10_000);
        publish(UUID.randomUUID(), "PaymentPartiallyRefunded", paymentId, merchantId, "PARTIALLY_REFUNDED", "CAPTURED", 4_000);
        publish(UUID.randomUUID(), "PaymentRefunded", paymentId, merchantId, "REFUNDED", "PARTIALLY_REFUNDED", 6_000);

        await().atMost(Duration.ofSeconds(15)).until(() -> ledgerEntryCountFor(paymentId) == 8); // 4 events x 2 legs

        assertThat(clearingBalance("USD")).isZero();
        assertThat(merchantBalance(AccountType.MERCHANT_PENDING, merchantId, "USD")).isZero();
        assertThat(merchantBalance(AccountType.MERCHANT_SETTLED, merchantId, "USD")).isZero();
    }

    @Test
    void redeliveringTheSameEventIsAnIdempotentNoOp() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        publish(eventId, "PaymentAuthorized", paymentId, merchantId, "AUTHORIZED", "CREATED", 5_000);
        await().atMost(Duration.ofSeconds(15)).until(() -> ledgerEntryCountFor(paymentId) == 2);

        publish(eventId, "PaymentAuthorized", paymentId, merchantId, "AUTHORIZED", "CREATED", 5_000);
        // Give the redelivery a moment to reach the consumer; it must not add more entries.
        Thread.sleep(2000);

        assertThat(ledgerEntryCountFor(paymentId)).isEqualTo(2);
        assertThat(processedEventRepository.existsByEventId(eventId)).isTrue();
    }

    @Test
    void voidingAfterAuthorizationReversesBackToZeroInARealDatabase() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();

        publish(UUID.randomUUID(), "PaymentAuthorized", paymentId, merchantId, "AUTHORIZED", "CREATED", 7_500);
        await().atMost(Duration.ofSeconds(15)).until(() -> ledgerEntryCountFor(paymentId) == 2);

        publish(UUID.randomUUID(), "PaymentVoided", paymentId, merchantId, "VOIDED", "AUTHORIZED", 7_500);
        await().atMost(Duration.ofSeconds(15)).until(() -> ledgerEntryCountFor(paymentId) == 4);

        assertThat(clearingBalance("USD")).isZero();
        assertThat(merchantBalance(AccountType.MERCHANT_PENDING, merchantId, "USD")).isZero();
    }

    @Test
    void concurrentPostingsToTheSameSharedClearingAccountRetryAndNeverLoseAnUpdate() throws Exception {
        // A currency no other test method touches: this test deliberately leaves the
        // clearing account at a non-zero balance, unlike the others (which net to
        // zero by design) — sharing "USD" with them would make this test's outcome
        // depend on execution order.
        String currency = "CHF";
        int concurrentEvents = 10;
        long amountEach = 1_000;
        ExecutorService executor = Executors.newFixedThreadPool(concurrentEvents);

        List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < concurrentEvents; i++) {
            UUID paymentId = UUID.randomUUID();
            UUID merchantId = UUID.randomUUID();
            futures.add(executor.submit(() -> ledgerService.processEvent(
                    envelope(UUID.randomUUID(), "PaymentAuthorized", paymentId, merchantId,
                            "AUTHORIZED", "CREATED", amountEach, currency))));
        }
        for (var future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // Every event shares the one PLATFORM_CLEARING(CHF) account — if optimistic
        // locking + retry didn't work, concurrent lost updates would leave this short.
        assertThat(clearingBalance(currency)).isEqualTo(concurrentEvents * amountEach);
    }

    private long clearingBalance(String currency) {
        return accountRepository.findByAccountTypeAndOwnerIdAndCurrency(AccountType.PLATFORM_CLEARING, null, currency)
                .map(Account::getBalanceMinor)
                .orElse(0L);
    }

    private long merchantBalance(AccountType type, UUID merchantId, String currency) {
        return accountRepository.findByAccountTypeAndOwnerIdAndCurrency(type, merchantId, currency)
                .map(Account::getBalanceMinor)
                .orElse(0L);
    }

    /** Ledger entries don't carry paymentId directly — join through their ledger_transaction. */
    private long ledgerEntryCountFor(UUID paymentId) {
        List<LedgerTransaction> transactions = ledgerTransactionRepository.findByPaymentIdOrderByCreatedAtAsc(paymentId);
        return transactions.stream()
                .mapToLong(txn -> ledgerEntryRepository.findByLedgerTransactionId(txn.getId()).size())
                .sum();
    }

    private EventEnvelope<PaymentLedgerEventPayload> envelope(UUID eventId, String eventType, UUID paymentId,
                                                              UUID merchantId, String status, String previousStatus,
                                                              long eventAmountMinor) {
        return envelope(eventId, eventType, paymentId, merchantId, status, previousStatus, eventAmountMinor, "USD");
    }

    private EventEnvelope<PaymentLedgerEventPayload> envelope(UUID eventId, String eventType, UUID paymentId,
                                                              UUID merchantId, String status, String previousStatus,
                                                              long eventAmountMinor, String currency) {
        PaymentLedgerEventPayload payload = new PaymentLedgerEventPayload(
                paymentId, merchantId, 10_000, currency, status, previousStatus, eventAmountMinor);
        return new EventEnvelope<>(eventId, eventType, paymentId.toString(),
                java.time.Instant.now(), "test-correlation", payload);
    }

    private void publish(UUID eventId, String eventType, UUID paymentId, UUID merchantId, String status,
                         String previousStatus, long eventAmountMinor) throws Exception {
        String json = objectMapper.writeValueAsString(
                envelope(eventId, eventType, paymentId, merchantId, status, previousStatus, eventAmountMinor));
        producer.send(new ProducerRecord<>(TOPIC, paymentId.toString(), json)).get(5, TimeUnit.SECONDS);
    }
}
