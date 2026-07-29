package com.paymentflow.sandbox;

import com.paymentflow.testsupport.openapi.PublicApiResponseContract;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * sandbox-service's real responses, validated against {@code docs/openapi.yaml} (M21.7).
 *
 * <p>Includes the platform's one unauthenticated public call. {@code GET /v1/test/cards} is
 * made here with **no headers at all**, which is the only way to prove that the document's
 * {@code security: []} describes the running system rather than merely asserting it: an
 * endpoint that had quietly started requiring a key would 401 here, and the expected-status
 * check would fail before any schema was consulted.
 */
@SpringBootTest
@Testcontainers
class PublicApiContractIntegrationTest extends PublicApiResponseContract {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @Override
    protected Set<String> publicPaths() {
        return Set.of(
                "/v1/test/cards",
                "/v1/test/simulations",
                "/v1/test/simulations/active",
                "/v1/test/decisions",
                "/v1/test/decisions/payments/{paymentId}");
    }

    @Override
    protected List<ContractCall> contractCalls() {
        UUID merchantId = UUID.randomUUID();
        HttpHeaders context = signedContext(merchantId, "test", "payments:write");

        return List.of(
                // No credential, deliberately — see the class comment.
                ContractCall.get("/v1/test/cards", null, 200),
                // No override is active for a fresh merchant, which is a documented 404
                // rather than an empty body.
                ContractCall.get("/v1/test/simulations/active", context, 404),
                ContractCall.post("/v1/test/simulations", context,
                        """
                        {"scenario": "FORCE_DECLINE", "declineCode": "insufficient_funds", \
                        "remainingCount": 3}""", 201),
                ContractCall.get("/v1/test/simulations/active", context, 200),
                // The documented 400: a scenario whose required field is missing.
                ContractCall.post("/v1/test/simulations", context,
                        "{\"scenario\": \"FORCE_DECLINE\", \"remainingCount\": 1}", 400),
                ContractCall.delete("/v1/test/simulations/active", context, 204),
                // Empty, but the offset page envelope (D139) is real and still has to match.
                ContractCall.get("/v1/test/decisions", context, 200),
                ContractCall.get("/v1/test/decisions/payments/" + UUID.randomUUID(), context, 200));
    }
}
