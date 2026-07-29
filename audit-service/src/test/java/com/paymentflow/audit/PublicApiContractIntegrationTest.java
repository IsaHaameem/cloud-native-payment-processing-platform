package com.paymentflow.audit;

import com.paymentflow.audit.domain.AuditLogEntry;
import com.paymentflow.audit.repository.AuditLogEntryRepository;
import com.paymentflow.common.dto.event.CanonicalEventType;
import com.paymentflow.testsupport.openapi.PublicApiResponseContract;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * audit-service's real responses, validated against {@code docs/openapi.yaml} (M21.7).
 *
 * <p>This is the service whose document was most wrong before M21.7, and the reason is worth
 * keeping attached to the test that would have caught it: {@code EventResponse.data} is a
 * Jackson {@code JsonNode}, and springdoc described the Java class rather than the payload —
 * publishing {@code isArray}, {@code isBigDecimal} and eighteen other bean getters as the
 * shape of a webhook body. A real event validated against none of it. The fix was to declare
 * the field a free-form object; this test is what makes the fix a fact rather than a claim.
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@Testcontainers
class PublicApiContractIntegrationTest extends PublicApiResponseContract {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @Autowired
    private AuditLogEntryRepository repository;

    @Override
    protected Set<String> publicPaths() {
        return Set.of("/v1/events", "/v1/events/{id}");
    }

    @Override
    protected List<ContractCall> contractCalls() {
        UUID merchantId = UUID.randomUUID();
        HttpHeaders read = signedContext(merchantId, "test", "events:read");

        AuditLogEntry entry = repository.saveAndFlush(AuditLogEntry.of(
                UUID.randomUUID(), "PaymentCaptured", UUID.randomUUID().toString(), Instant.now(),
                "corr-contract", "test", merchantId,
                // A realistic payload: this is the tree that goes into `data`, and the whole
                // point of the schema fix is that an arbitrary object validates here.
                "{\"paymentId\":\"" + UUID.randomUUID() + "\",\"amountMinor\":5000,"
                        + "\"currency\":\"USD\",\"metadata\":{\"orderId\":\"A-1234\"}}"));

        return List.of(
                ContractCall.get("/v1/events", read, 200),
                ContractCall.get("/v1/events?type=payment.captured", read, 200),
                ContractCall.get("/v1/events/" + CanonicalEventType.eventRefFor(entry.getEventId()), read, 200),
                // The two documented failures, which are genuinely different: a malformed id
                // is the caller's bug (400) and a well-formed id nobody owns is not (404).
                ContractCall.get("/v1/events/evt_zzzz", read, 400),
                ContractCall.get("/v1/events/" + CanonicalEventType.eventRefFor(UUID.randomUUID()), read, 404));
    }
}
