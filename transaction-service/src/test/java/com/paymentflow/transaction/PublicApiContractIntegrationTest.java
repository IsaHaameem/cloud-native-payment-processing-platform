package com.paymentflow.transaction;

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
 * transaction-service's real responses, validated against {@code docs/openapi.yaml} (M21.7).
 *
 * <p>The ledger has no write endpoint by design — every posting comes from the
 * {@code payment.events} consumer (D42), which is what makes the double-entry guarantees
 * provable. So the fixtures here are genuinely empty, and that is the case worth checking on
 * this service: a merchant who has transacted in no currency gets an empty `balances` list
 * and an empty page, and both still have to be the shape the document publishes. An SDK that
 * broke on the first call a new merchant ever makes would be a poor start.
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@Testcontainers
class PublicApiContractIntegrationTest extends PublicApiResponseContract {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @Override
    protected Set<String> publicPaths() {
        return Set.of("/v1/balance", "/v1/balance_transactions");
    }

    @Override
    protected List<ContractCall> contractCalls() {
        HttpHeaders read = signedContext(UUID.randomUUID(), "test", "balance:read");

        return List.of(
                ContractCall.get("/v1/balance", read, 200),
                ContractCall.get("/v1/balance_transactions", read, 200),
                ContractCall.get("/v1/balance_transactions?limit=5", read, 200),
                // A tampered cursor is refused before it reaches the query, and the error
                // body it produces is part of the published contract too.
                ContractCall.get("/v1/balance_transactions?starting_after=not-a-cursor", read, 400));
    }
}
