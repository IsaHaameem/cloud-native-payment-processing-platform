package com.paymentflow.analytics;

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
 * analytics-service's real responses, validated against {@code docs/openapi.yaml} (M21.7).
 *
 * <p>The empty window is the interesting case here rather than a limitation. A merchant with
 * no traffic gets `successRate: null` — explicitly null, not omitted, because a rate over
 * zero attempts is unknown rather than zero (D143) — and that is exactly the response an SDK
 * generated from a document typing the field non-null would fail to deserialize. Validating
 * it here is what turns the document's nullability into a checked claim.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "paymentflow.request-log.partition-initial-delay-ms=3600000",
        "paymentflow.request-log.rollup-initial-delay-ms=3600000",
        "paymentflow.request-log.retention-initial-delay-ms=3600000"
})
@Testcontainers
class PublicApiContractIntegrationTest extends PublicApiResponseContract {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @Override
    protected Set<String> publicPaths() {
        return Set.of("/v1/analytics/payments", "/v1/request_logs", "/v1/usage");
    }

    @Override
    protected List<ContractCall> contractCalls() {
        UUID merchantId = UUID.randomUUID();
        HttpHeaders analytics = signedContext(merchantId, "test", "analytics:read");
        HttpHeaders logs = signedContext(merchantId, "test", "logs:read");

        return List.of(
                ContractCall.get("/v1/analytics/payments", analytics, 200),
                ContractCall.get(
                        "/v1/analytics/payments?from=2026-07-01T00:00:00Z&to=2026-07-29T00:00:00Z",
                        analytics, 200),
                ContractCall.get("/v1/request_logs", logs, 200),
                ContractCall.get("/v1/request_logs?status_code=409&method=POST", logs, 200),
                ContractCall.get("/v1/usage", logs, 200),
                ContractCall.get("/v1/usage?from=2026-07-01&to=2026-07-29", logs, 200));
    }
}
