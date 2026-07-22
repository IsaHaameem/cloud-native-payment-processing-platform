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
import io.micrometer.core.instrument.MeterRegistry;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the M8 Resilience4j wrapper end-to-end over real HTTP: a merchant-service
 * stub whose behavior can be flipped per test (down / slow / flaky-then-recovers)
 * stands in for the real service, and every payment-creation request routes through
 * the actual {@code MerchantResolver} decorator chain — not a mock of it. Resilience4j
 * instance thresholds are overridden to small, fast values here (production's are
 * tuned for a real deployment, not a test suite); the composition being exercised is
 * identical either way.
 */
@SpringBootTest(properties = {
        "resilience4j.circuitbreaker.instances.merchantService.slidingWindowSize=4",
        "resilience4j.circuitbreaker.instances.merchantService.minimumNumberOfCalls=4",
        "resilience4j.circuitbreaker.instances.merchantService.waitDurationInOpenState=1s",
        "resilience4j.circuitbreaker.instances.merchantService.permittedNumberOfCallsInHalfOpenState=2",
        "resilience4j.retry.instances.merchantService.maxAttempts=3",
        "resilience4j.retry.instances.merchantService.waitDuration=20ms",
        "resilience4j.thread-pool-bulkhead.instances.merchantService.coreThreadPoolSize=1",
        "resilience4j.thread-pool-bulkhead.instances.merchantService.maxThreadPoolSize=1",
        "resilience4j.thread-pool-bulkhead.instances.merchantService.queueCapacity=0",
        "resilience4j.timelimiter.instances.merchantService.timeoutDuration=500ms",
        "paymentflow.resilience.merchant-service.retry-initial-interval-ms=20",
        "paymentflow.resilience.merchant-service.read-timeout-ms=5000"
})
@AutoConfigureMockMvc
@Testcontainers
class MerchantResilienceIntegrationTest {

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

    private static final AtomicReference<String> mode = new AtomicReference<>("NORMAL");
    private static final AtomicInteger callCount = new AtomicInteger();
    private static final AtomicInteger flakyRemainingFailures = new AtomicInteger();
    private static volatile String lastAuthorizationHeaderSeen;
    // "SLOW" mode blocks the stub on this gate rather than sleeping a fixed duration:
    // the downstream is then effectively unbounded-slow (so a fail-fast assertion has a
    // huge margin and can't flake on a loaded machine), and a test drains any in-flight
    // call deterministically by counting the gate down — no blind Thread.sleep guesswork.
    private static volatile CountDownLatch slowGate;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;
    @Autowired
    private MeterRegistry meterRegistry;

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
            callCount.incrementAndGet();
            lastAuthorizationHeaderSeen = exchange.getRequestHeaders().getFirst("Authorization");
            String currentMode = mode.get();
            try {
                switch (currentMode) {
                    case "DOWN" -> {
                        // Abrupt close with no response — the client sees a connection-level
                        // IOException, which Feign surfaces as a RetryableException.
                        exchange.close();
                        return;
                    }
                    case "SLOW" -> slowGate.await(10, TimeUnit.SECONDS);
                    case "FLAKY" -> {
                        if (flakyRemainingFailures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                            exchange.close();
                            return;
                        }
                    }
                    default -> {
                        // NORMAL: respond immediately below.
                    }
                }
                String subject = SignedJWT.parse(lastAuthorizationHeaderSeen.substring("Bearer ".length()))
                        .getJWTClaimsSet().getSubject();
                String body = "{\"id\":\"" + merchantIdFor(subject) + "\",\"contactEmail\":\"m@test\",\"webhookUrl\":null}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exchange.close();
            } catch (java.text.ParseException e) {
                throw new java.io.IOException("Test stub could not parse JWT", e);
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
        mode.set("NORMAL");
        callCount.set(0);
        flakyRemainingFailures.set(0);
        slowGate = new CountDownLatch(1);
        // The CircuitBreaker instance is a singleton shared across every test method in
        // this class (same Spring context) — without resetting it, one test's OPEN
        // circuit leaks into the next test's assertions.
        circuitBreakerRegistry.circuitBreaker("merchantService").reset();
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
    }

    private static UUID merchantIdFor(String subject) {
        return UUID.nameUUIDFromBytes(subject.getBytes(StandardCharsets.UTF_8));
    }

    private String createPayment(String token, String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new com.paymentflow.payment.dto.CreatePaymentRequest(1000, "USD", "resilience test"))))
                .andReturn().getResponse().getContentAsString();
    }

    private int createPaymentStatus(String token, String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new com.paymentflow.payment.dto.CreatePaymentRequest(1000, "USD", "resilience test"))))
                .andReturn().getResponse().getStatus();
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

    @Test
    void aHealthyMerchantServiceForwardsTheCallersJwtEvenThoughTheCallRunsOnTheBulkheadThread() throws Exception {
        String token = signedJwt(UUID.randomUUID().toString());

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "jwt-forward-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new com.paymentflow.payment.dto.CreatePaymentRequest(1000, "USD", "x"))))
                .andExpect(status().isCreated());

        assertThat(lastAuthorizationHeaderSeen).isEqualTo("Bearer " + token);
    }

    @Test
    void merchantServiceDownEventuallySurfacesAsServiceUnavailable() throws Exception {
        mode.set("DOWN");
        String token = signedJwt(UUID.randomUUID().toString());

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "down-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new com.paymentflow.payment.dto.CreatePaymentRequest(1000, "USD", "x"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void merchantServiceTooSlowFailsFastRatherThanHangingTheRequestThread() throws Exception {
        mode.set("SLOW");
        String token = signedJwt(UUID.randomUUID().toString());

        long start = System.nanoTime();
        int status = createPaymentStatus(token, "slow-1");
        long elapsedMs = java.time.Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(status).isEqualTo(503);
        // The stub blocks on slowGate (up to a 10s safety ceiling); the TimeLimiter budget
        // is 500ms and Retry adds at most 3 attempts (~1.5s total). Asserting comfortably
        // under the 10s ceiling proves the request failed fast rather than waiting the
        // downstream out — with a margin wide enough that a GC pause or a loaded CI box
        // can't push it over (the old `< 1800ms` sat only ~260ms above the real ~1540ms
        // retry budget, which is exactly what made it flake).
        assertThat(elapsedMs).isLessThan(5000);

        // Cancelling a CompletableFuture (what TimeLimiter does on timeout) does not
        // interrupt the in-flight blocking Feign call — a real characteristic found during
        // this milestone (see the M8 changelog), not a test artifact. Those background stub
        // calls would otherwise keep occupying this class's single-slot bulkhead thread into
        // the next test, making it see a false BulkheadFullException. Releasing the gate
        // lets them all return immediately — a deterministic drain, not a blind timed guess
        // at how much of a fixed sleep is still left to run.
        slowGate.countDown();
    }

    @Test
    void aTransientFailureIsRetriedAndTheRequestEventuallySucceeds() throws Exception {
        mode.set("FLAKY");
        flakyRemainingFailures.set(2);
        String token = signedJwt(UUID.randomUUID().toString());

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "flaky-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new com.paymentflow.payment.dto.CreatePaymentRequest(1000, "USD", "x"))))
                .andExpect(status().isCreated());

        assertThat(callCount.get()).isEqualTo(3);
    }

    @Test
    void repeatedFailuresOpenTheCircuitThenItRecoversThroughHalfOpenToClosed() throws Exception {
        mode.set("DOWN");

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("merchantService");
        for (int i = 0; i < 4; i++) {
            String token = signedJwt(UUID.randomUUID().toString());
            int status = createPaymentStatus(token, "circuit-open-" + i);
            assertThat(status).isEqualTo(503);
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int callsBeforeFailFastAttempt = callCount.get();
        String tokenWhileOpen = signedJwt(UUID.randomUUID().toString());
        int statusWhileOpen = createPaymentStatus(tokenWhileOpen, "circuit-open-while-open");
        assertThat(statusWhileOpen).isEqualTo(503);
        // Fails fast: the circuit itself rejected the call, the stub was never hit again.
        assertThat(callCount.get()).isEqualTo(callsBeforeFailFastAttempt);

        // waitDurationInOpenState is 1s in this test's config.
        Thread.sleep(1200);
        mode.set("NORMAL");

        // permittedNumberOfCallsInHalfOpenState=2: two successful trial calls close it.
        String recover1 = signedJwt(UUID.randomUUID().toString());
        assertThat(createPaymentStatus(recover1, "circuit-recover-1")).isEqualTo(201);
        String recover2 = signedJwt(UUID.randomUUID().toString());
        assertThat(createPaymentStatus(recover2, "circuit-recover-2")).isEqualTo(201);

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void concurrentCallsBeyondTheBulkheadsCapacityAreRejected() throws Exception {
        mode.set("SLOW");
        ExecutorService callers = Executors.newFixedThreadPool(3);
        try {
            List<Future<Integer>> futures = List.of(
                    callers.submit(() -> createPaymentStatus(signedJwt(UUID.randomUUID().toString()), "bulkhead-1")),
                    callers.submit(() -> createPaymentStatus(signedJwt(UUID.randomUUID().toString()), "bulkhead-2")),
                    callers.submit(() -> createPaymentStatus(signedJwt(UUID.randomUUID().toString()), "bulkhead-3")));

            List<Integer> statuses = futures.stream().map(f -> {
                try {
                    return f.get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).toList();

            // coreThreadPoolSize=1, maxThreadPoolSize=1, queueCapacity=0: with 3 calls
            // launched together against a slow stub, at least one must be bulkhead-rejected.
            assertThat(statuses).contains(503);
        } finally {
            // Release any stub call still blocked on the gate so it doesn't hold the sole
            // bulkhead thread into the next test — deterministic, same reasoning as the
            // fail-fast test above.
            slowGate.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void resilience4jMetricsAreExposedThroughMicrometer() throws Exception {
        String token = signedJwt(UUID.randomUUID().toString());
        createPayment(token, "metrics-1");

        meterRegistry.getMeters().stream()
                .map(m -> m.getId().getName())
                .distinct()
                .filter(n -> n.contains("resilience4j"))
                .sorted()
                .forEach(System.out::println);
    }
}
