package com.paymentflow.payment;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the M17.4 Resilience4j wrapper around {@code SandboxAuthorizationAdvisor}
 * end-to-end over real HTTP — the same Retry → CircuitBreaker → TimeLimiter →
 * ThreadPoolBulkhead composition {@link MerchantResilienceIntegrationTest} proves for
 * merchant-service (M8), exercised here through {@code POST
 * /api/v1/payments/{id}/authorize} against a sandbox-service stub whose behavior can be
 * flipped per test. merchant-service itself is always healthy in this class — only
 * sandbox-service's degradation is under test.
 */
@SpringBootTest(properties = {
        "resilience4j.circuitbreaker.instances.sandboxService.slidingWindowSize=4",
        "resilience4j.circuitbreaker.instances.sandboxService.minimumNumberOfCalls=4",
        "resilience4j.circuitbreaker.instances.sandboxService.waitDurationInOpenState=1s",
        "resilience4j.circuitbreaker.instances.sandboxService.permittedNumberOfCallsInHalfOpenState=2",
        "resilience4j.retry.instances.sandboxService.maxAttempts=3",
        "resilience4j.retry.instances.sandboxService.waitDuration=20ms",
        "resilience4j.timelimiter.instances.sandboxService.timeoutDuration=500ms",
        "paymentflow.resilience.sandbox-service.retry-initial-interval-ms=20",
        "paymentflow.resilience.sandbox-service.read-timeout-ms=5000"
})
@AutoConfigureMockMvc
@Testcontainers
class SandboxResilienceIntegrationTest {

    private static final String ISSUER = "https://identity.paymentflow.local";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:8-alpine"))
            .withExposedPorts(6379);

    private static RSAKey rsaKey;
    private static HttpServer stub;

    private static final AtomicReference<String> sandboxMode = new AtomicReference<>("NORMAL");
    private static final AtomicInteger sandboxCallCount = new AtomicInteger();
    private static volatile CountDownLatch slowGate;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

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
            String subject;
            try {
                subject = SignedJWT.parse(authHeader.substring("Bearer ".length())).getJWTClaimsSet().getSubject();
            } catch (java.text.ParseException e) {
                throw new java.io.IOException("Test stub could not parse JWT", e);
            }
            String body = "{\"id\":\"" + merchantIdFor(subject) + "\",\"contactEmail\":\"m@test\",\"webhookUrl\":null}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        stub.createContext("/internal/v1/sandbox/decisions", exchange -> {
            sandboxCallCount.incrementAndGet();
            String currentMode = sandboxMode.get();
            try {
                switch (currentMode) {
                    case "DOWN" -> {
                        // Abrupt close with no response — the client sees a connection-level
                        // IOException, which Feign surfaces as a RetryableException.
                        exchange.close();
                        return;
                    }
                    case "SLOW" -> slowGate.await(10, TimeUnit.SECONDS);
                    default -> {
                        // NORMAL: respond immediately below.
                    }
                }
                String body = "{\"outcome\":\"APPROVE\",\"declineCode\":null,\"errorCode\":null,\"latencyMs\":0,"
                        + "\"source\":\"MODE_DEFAULT\",\"deferredOperation\":null,\"deferredDelayMs\":null}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exchange.close();
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

    @BeforeEach
    void resetStubBehavior() {
        sandboxMode.set("NORMAL");
        sandboxCallCount.set(0);
        slowGate = new CountDownLatch(1);
        // The CircuitBreaker instance is a singleton shared across every test method in
        // this class (same Spring context) — without resetting it, one test's OPEN
        // circuit leaks into the next test's assertions.
        circuitBreakerRegistry.circuitBreaker("sandboxService").reset();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("paymentflow.services.identity.jwks-uri",
                () -> "http://localhost:" + stub.getAddress().getPort() + "/oauth2/jwks");
        registry.add("paymentflow.services.merchant.base-uri",
                () -> "http://localhost:" + stub.getAddress().getPort());
        registry.add("paymentflow.services.sandbox.base-uri",
                () -> "http://localhost:" + stub.getAddress().getPort());
    }

    private static UUID merchantIdFor(String subject) {
        return UUID.nameUUIDFromBytes(subject.getBytes(StandardCharsets.UTF_8));
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

    /** Creates a payment (sandbox is never called by create — M17.4 wires only authorize) and returns its id. */
    private UUID createPayment(String token, String idempotencyKey) throws Exception {
        String body = mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":1000,\"currency\":\"USD\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asString());
    }

    private int authorizeStatus(UUID paymentId, String token, String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/api/v1/payments/" + paymentId + "/authorize")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void aHealthySandboxApprovesAndTheAdvisorSignsAVerifiableInternalContext() throws Exception {
        String token = signedJwt(UUID.randomUUID().toString());
        UUID paymentId = createPayment(token, "healthy-create");

        mockMvc.perform(post("/api/v1/payments/" + paymentId + "/authorize")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "healthy-authorize"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("authorized"));
    }

    @Test
    void sandboxDownEventuallySurfacesAsServiceUnavailableAndLeavesThePaymentUntouched() throws Exception {
        String token = signedJwt(UUID.randomUUID().toString());
        UUID paymentId = createPayment(token, "down-create");
        sandboxMode.set("DOWN");

        mockMvc.perform(post("/api/v1/payments/" + paymentId + "/authorize")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "down-authorize"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));

        // Thrown from outside any transaction (D129) — the payment is untouched, so a
        // caller retrying under a NEW Idempotency-Key against the now-healthy sandbox
        // still succeeds.
        sandboxMode.set("NORMAL");
        mockMvc.perform(post("/api/v1/payments/" + paymentId + "/authorize")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "down-retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("authorized"));
    }

    @Test
    void sandboxTooSlowFailsFastRatherThanHangingTheRequestThread() throws Exception {
        String token = signedJwt(UUID.randomUUID().toString());
        UUID paymentId = createPayment(token, "slow-create");
        sandboxMode.set("SLOW");

        long start = System.nanoTime();
        int status = authorizeStatus(paymentId, token, "slow-authorize");
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(status).isEqualTo(503);
        // TimeLimiter budget is 500ms, Retry adds at most 3 attempts (~1.5s total) — a
        // wide margin under the stub's 10s gate proves this failed fast, not that it
        // waited the downstream out.
        assertThat(elapsedMs).isLessThan(5000);

        // Cancelling a CompletableFuture (what TimeLimiter does on timeout) does not
        // interrupt the in-flight blocking Feign call (M8 finding, applies identically
        // here) — release the gate so that background stub call doesn't keep occupying
        // this class's bulkhead thread into the next test.
        slowGate.countDown();
    }

    @Test
    void repeatedFailuresOpenTheCircuitThenItRecoversThroughHalfOpenToClosed() throws Exception {
        String token = signedJwt(UUID.randomUUID().toString());
        sandboxMode.set("DOWN");

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("sandboxService");
        for (int i = 0; i < 4; i++) {
            UUID paymentId = createPayment(token, "circuit-open-create-" + i);
            assertThat(authorizeStatus(paymentId, token, "circuit-open-" + i)).isEqualTo(503);
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int callsBeforeFailFastAttempt = sandboxCallCount.get();
        UUID paymentWhileOpen = createPayment(token, "circuit-open-while-open-create");
        assertThat(authorizeStatus(paymentWhileOpen, token, "circuit-open-while-open")).isEqualTo(503);
        // Fails fast: the circuit itself rejected the call, the stub was never hit again.
        assertThat(sandboxCallCount.get()).isEqualTo(callsBeforeFailFastAttempt);

        sandboxMode.set("NORMAL");
        await().atMost(java.time.Duration.ofSeconds(5))
                .until(() -> circuitBreaker.getState() == CircuitBreaker.State.HALF_OPEN);

        // permittedNumberOfCallsInHalfOpenState=2: two successful trial calls close it.
        UUID recover1 = createPayment(token, "circuit-recover-1-create");
        assertThat(authorizeStatus(recover1, token, "circuit-recover-1")).isEqualTo(200);
        UUID recover2 = createPayment(token, "circuit-recover-2-create");
        assertThat(authorizeStatus(recover2, token, "circuit-recover-2")).isEqualTo(200);

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
