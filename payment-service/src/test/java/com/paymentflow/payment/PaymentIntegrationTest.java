package com.paymentflow.payment;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.paymentflow.payment.repository.OutboxEventRepository;
import com.paymentflow.payment.repository.PaymentRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full HTTP-level payment lifecycle against real Postgres and Redis. JWTs are signed
 * locally with a test RSA key; a single JDK {@link HttpServer} stub serves both
 * identity's JWKS and merchant-service's {@code /api/v1/merchants/me} (deriving a
 * deterministic per-subject merchant id from the JWT it's handed — payment-service has
 * already verified that JWT's signature via Spring Security before this stub ever sees
 * it, so the stub only needs to parse it, not re-verify it). No Kafka here — the outbox
 * row being written is asserted directly; the relay-to-Kafka pipeline is covered
 * separately in {@code OutboxRelayIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PaymentIntegrationTest {

    private static final String ISSUER = "https://identity.paymentflow.local";
    private static final String NO_MERCHANT_SUBJECT = "no-merchant-user";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:8-alpine"))
            .withExposedPorts(6379);

    private static RSAKey rsaKey;
    private static HttpServer stub;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @BeforeAll
    static void startStub() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID("test-key")
                .build();
        String jwksJson = new JWKSet(rsaKey.toPublicJWK()).toString();

        stub = HttpServer.create(new InetSocketAddress(0), 0);
        stub.createContext("/oauth2/jwks", exchange -> {
            byte[] bytes = jwksJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) {
                body.write(bytes);
            }
        });
        stub.createContext("/api/v1/merchants/me", exchange -> {
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            String token = authHeader.substring("Bearer ".length());
            String subject;
            try {
                subject = SignedJWT.parse(token).getJWTClaimsSet().getSubject();
            } catch (java.text.ParseException e) {
                throw new java.io.IOException("Test stub could not parse JWT", e);
            }
            if (NO_MERCHANT_SUBJECT.equals(subject)) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            String body = "{\"id\":\"" + merchantIdFor(subject) + "\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        stub.start();
    }

    @AfterAll
    static void stopStub() {
        if (stub != null) {
            stub.stop(0);
        }
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("paymentflow.services.identity.jwks-uri", () -> "http://localhost:" + stub.getAddress().getPort() + "/oauth2/jwks");
        registry.add("paymentflow.services.merchant.base-uri", () -> "http://localhost:" + stub.getAddress().getPort());
    }

    private static UUID merchantIdFor(String subject) {
        return UUID.nameUUIDFromBytes(subject.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void fullLifecycleCreateAuthorizeCaptureRefund() throws Exception {
        String token = signedJwt(UUID.randomUUID().toString());

        String createBody = mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "lifecycle-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountMinor":10000,"currency":"USD","description":"order #1"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn().getResponse().getContentAsString();
        UUID paymentId = UUID.fromString(objectMapper.readTree(createBody).get("id").asString());

        mockMvc.perform(post("/api/v1/payments/" + paymentId + "/authorize")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "lifecycle-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));

        mockMvc.perform(post("/api/v1/payments/" + paymentId + "/capture")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "lifecycle-3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.capturedAmountMinor").value(10000));

        mockMvc.perform(post("/api/v1/payments/" + paymentId + "/refund")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "lifecycle-4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundedAmountMinor").value(10000));

        List<String> eventTypes = outboxEventRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(paymentId))
                .map(com.paymentflow.payment.domain.OutboxEvent::getEventType)
                .toList();
        assertThat(eventTypes).containsExactlyInAnyOrder(
                "PaymentCreated", "PaymentAuthorized", "PaymentCaptured", "PaymentRefunded");
    }

    @Test
    void partialRefundsAccumulateToFullyRefunded() throws Exception {
        String token = signedJwt(UUID.randomUUID().toString());
        UUID paymentId = createAuthorizedAndCaptured(token, 10000, "partial-1");

        mockMvc.perform(post("/api/v1/payments/" + paymentId + "/refund")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "partial-refund-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":4000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"));

        mockMvc.perform(post("/api/v1/payments/" + paymentId + "/refund")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "partial-refund-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":6000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundedAmountMinor").value(10000));
    }

    @Test
    void refundExceedingRemainingAmountIsRejected() throws Exception {
        String token = signedJwt(UUID.randomUUID().toString());
        UUID paymentId = createAuthorizedAndCaptured(token, 5000, "over-refund");

        mockMvc.perform(post("/api/v1/payments/" + paymentId + "/refund")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "over-refund-attempt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":9000}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void capturingBeforeAuthorizationIsRejectedWith409() throws Exception {
        String token = signedJwt(UUID.randomUUID().toString());

        String createBody = mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "illegal-transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":1000,\"currency\":\"USD\"}"))
                .andReturn().getResponse().getContentAsString();
        UUID paymentId = UUID.fromString(objectMapper.readTree(createBody).get("id").asString());

        mockMvc.perform(post("/api/v1/payments/" + paymentId + "/capture")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "illegal-capture"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void creatingWithoutIdempotencyKeyIsRejected() throws Exception {
        String token = signedJwt(UUID.randomUUID().toString());

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":1000,\"currency\":\"USD\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void replayingTheSameIdempotencyKeyAndBodyReturnsTheSameResponseWithoutDuplicating() throws Exception {
        String token = signedJwt(UUID.randomUUID().toString());
        String body = "{\"amountMinor\":2500,\"currency\":\"EUR\",\"description\":\"replay test\"}";

        String first = mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "replay-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "replay-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(first).isEqualTo(second);
        UUID paymentId = UUID.fromString(objectMapper.readTree(first).get("id").asString());
        long matching = paymentRepository.findAll().stream().filter(p -> p.getId().equals(paymentId)).count();
        assertThat(matching).isEqualTo(1);
    }

    @Test
    void reusingAnIdempotencyKeyWithADifferentBodyIsRejected() throws Exception {
        String token = signedJwt(UUID.randomUUID().toString());

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "reuse-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":1000,\"currency\":\"USD\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "reuse-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":9999,\"currency\":\"USD\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void concurrentRequestsWithTheSameIdempotencyKeyProduceExactlyOnePayment() throws Exception {
        String token = signedJwt(UUID.randomUUID().toString());
        String body = "{\"amountMinor\":3000,\"currency\":\"USD\",\"description\":\"race\"}";
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Callable<Integer> task = () -> {
            barrier.await();
            return mockMvc.perform(post("/api/v1/payments")
                            .header("Authorization", "Bearer " + token)
                            .header("Idempotency-Key", "race-key")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn().getResponse().getStatus();
        };

        Future<Integer> first = executor.submit(task);
        Future<Integer> second = executor.submit(task);
        int statusA = first.get();
        int statusB = second.get();
        executor.shutdown();

        // Whichever way the race resolves (one 409'd, or both replayed the same
        // stored response), the invariant that actually matters is durable: exactly
        // one payment must exist for this idempotency key.
        assertThat(List.of(statusA, statusB)).allMatch(s -> s == 201 || s == 409);
        long count = paymentRepository.findAll().stream()
                .filter(p -> p.getAmountMinor() == 3000 && "race".equals(p.getDescription()))
                .count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void merchantACannotSeeMerchantBsPayment() throws Exception {
        String tokenA = signedJwt(UUID.randomUUID().toString());
        String tokenB = signedJwt(UUID.randomUUID().toString());

        String createBody = mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", "cross-tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":1000,\"currency\":\"USD\"}"))
                .andReturn().getResponse().getContentAsString();
        UUID paymentId = UUID.fromString(objectMapper.readTree(createBody).get("id").asString());

        mockMvc.perform(get("/api/v1/payments/" + paymentId).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/payments/" + paymentId).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());
    }

    @Test
    void creatingAPaymentWithoutAnOnboardedMerchantIsRejected() throws Exception {
        String token = signedJwt(NO_MERCHANT_SUBJECT);

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "no-merchant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":1000,\"currency\":\"USD\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void creatingWithoutATokenReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "no-auth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":1000,\"currency\":\"USD\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void negativeAmountFailsValidation() throws Exception {
        String token = signedJwt(UUID.randomUUID().toString());

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "bad-amount")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":-500,\"currency\":\"USD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private UUID createAuthorizedAndCaptured(String token, long amountMinor, String idempotencyKeyPrefix) throws Exception {
        String createBody = mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKeyPrefix + "-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":" + amountMinor + ",\"currency\":\"USD\"}"))
                .andReturn().getResponse().getContentAsString();
        UUID paymentId = UUID.fromString(objectMapper.readTree(createBody).get("id").asString());

        mockMvc.perform(post("/api/v1/payments/" + paymentId + "/authorize")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKeyPrefix + "-authorize"));
        mockMvc.perform(post("/api/v1/payments/" + paymentId + "/capture")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKeyPrefix + "-capture"));
        return paymentId;
    }

    private static String signedJwt(String subject) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(ISSUER)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(15, ChronoUnit.MINUTES)))
                .claim("email", subject + "@example.com")
                .claim("roles", List.of("USER"))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(rsaKey.toRSAPrivateKey()));
        return jwt.serialize();
    }
}
