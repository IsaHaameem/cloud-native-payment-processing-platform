package com.paymentflow.payment;

import com.paymentflow.payment.domain.Payment;
import com.paymentflow.payment.repository.PaymentRepository;
import com.paymentflow.testsupport.openapi.PublicApiResponseContract;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * payment-service's real responses, validated against {@code docs/openapi.yaml} (M21.7,
 * §5/M21 task 6).
 *
 * <p>The completion criterion this closes is *"live responses validate against the spec —
 * verified, not assumed"*. Everything else in M21 describes the API; this is the only thing
 * that checks the description is true.
 */
@SpringBootTest
@Testcontainers
class PublicApiContractIntegrationTest extends PublicApiResponseContract {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:8-alpine"))
            .withExposedPorts(6379);

    @Autowired
    private PaymentRepository paymentRepository;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("paymentflow.services.identity.jwks-uri", () -> "http://localhost:1/oauth2/jwks");
        registry.add("paymentflow.services.merchant.base-uri", () -> "http://localhost:1");
    }

    @Override
    protected Set<String> publicPaths() {
        return Set.of(
                "/v1/payments",
                "/v1/payments/{id}",
                "/v1/payments/{id}/authorize",
                "/v1/payments/{id}/capture",
                "/v1/payments/{id}/refund",
                "/v1/payments/{id}/void",
                "/v1/refunds",
                "/v1/refunds/{id}");
    }

    /**
     * The four state transitions are excluded, with the reason stated rather than left to be
     * inferred: each one calls sandbox-service through the {@code AuthorizationAdvisor} port
     * and, for a capture, the outbox relay — collaborators this class does not stand up. They
     * are exercised end to end against the running platform elsewhere; what would be gained
     * here is schema validation of a {@code PaymentResponse}, and the reads below already
     * validate that exact schema against the same document.
     */
    @Override
    protected Set<String> uncoveredOperations() {
        return Set.of(
                "POST /v1/payments/{id}/authorize",
                "POST /v1/payments/{id}/capture",
                "POST /v1/payments/{id}/refund",
                "POST /v1/payments/{id}/void");
    }

    @Override
    protected List<ContractCall> contractCalls() {
        UUID merchantId = UUID.randomUUID();
        HttpHeaders read = signedContext(merchantId, "test", "payments:read");
        HttpHeaders write = signedContext(merchantId, "test", "payments:read,payments:write");
        // Required on every mutation, which is a fact this very test established: the first
        // run omitted it, got a 400, and the document had been publishing it as optional.
        write.set("Idempotency-Key", UUID.randomUUID().toString());
        // A second key, because replaying the first would return the original response
        // rather than exercising the validation path the call below is there to check.
        HttpHeaders writeAgain = signedContext(merchantId, "test", "payments:read,payments:write");
        writeAgain.set("Idempotency-Key", UUID.randomUUID().toString());

        Payment payment = paymentRepository.saveAndFlush(
                Payment.create(merchantId, "test", 1000L, "USD", "Order A-1234", null,
                        "{\"orderId\":\"A-1234\"}"));
        // A second one so the list has more than a single element and the page envelope is
        // exercised with real content rather than an edge case.
        paymentRepository.saveAndFlush(
                Payment.create(merchantId, "test", 2500L, "USD", "Order A-1235", null, null));

        return List.of(
                ContractCall.get("/v1/payments", read, 200),
                ContractCall.get("/v1/payments?limit=1", read, 200),
                ContractCall.get("/v1/payments/" + payment.getId(), read, 200),
                // The documented 404: another merchant's payment is not found, never
                // forbidden (D102), and the error body has to validate too.
                ContractCall.get("/v1/payments/" + UUID.randomUUID(),
                        signedContext(UUID.randomUUID(), "test", "payments:read"), 404),
                ContractCall.get("/v1/refunds", read, 200),
                ContractCall.get("/v1/refunds/" + UUID.randomUUID(), read, 404),
                // Creation goes through the real handler, validation and mapper — the only
                // mutation on this tier that reaches no other service.
                ContractCall.post("/v1/payments", write,
                        """
                        {"amountMinor": 4200, "currency": "USD", "description": "Contract test", \
                        "metadata": {"orderId": "A-9"}}""", 201),
                // And its documented 400, so the validation error envelope is checked as
                // well as the happy path.
                ContractCall.post("/v1/payments", writeAgain,
                        "{\"amountMinor\": -1, \"currency\": \"USD\"}", 400));
    }
}
