package com.paymentflow.notification;

import com.paymentflow.testsupport.openapi.PublicApiResponseContract;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * notification-service's real responses, validated against {@code docs/openapi.yaml}
 * (M21.7).
 *
 * <p>The only service whose fixtures are built through its own public API rather than
 * through repositories, because it can be: an endpoint is created by calling
 * {@code POST /v1/webhook_endpoints}. That makes the seeding itself part of what is
 * verified — the object the rest of the calls read back is one the API actually produced,
 * not one assembled beside it.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "paymentflow.webhooks.require-https=false"
})
@Testcontainers
class PublicApiContractIntegrationTest extends PublicApiResponseContract {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @Override
    protected Set<String> publicPaths() {
        return Set.of(
                "/v1/webhook_endpoints",
                "/v1/webhook_endpoints/{id}",
                "/v1/webhook_endpoints/{id}/rotate_secret",
                "/v1/webhook_deliveries",
                "/v1/webhook_deliveries/{id}",
                "/v1/webhook_deliveries/{id}/replay");
    }

    /**
     * A delivery cannot be created through the public API — deliveries are produced by the
     * fan-out consuming {@code payment.events}, which this class does not run — so there is
     * no id to retrieve or replay. The list endpoint is exercised, which validates the
     * delivery page envelope; the two id-scoped operations are named here rather than left
     * to look like an oversight.
     */
    @Override
    protected Set<String> uncoveredOperations() {
        return Set.of(
                "GET /v1/webhook_deliveries/{id}",
                "POST /v1/webhook_deliveries/{id}/replay");
    }

    @Override
    protected List<ContractCall> contractCalls() throws Exception {
        UUID merchantId = UUID.randomUUID();
        HttpHeaders manage = signedContext(merchantId, "test", "webhooks:manage");

        // Created through the API itself, so the id below belongs to an object this service
        // really produced.
        MvcResult created = mockMvc.perform(post("/v1/webhook_endpoints")
                        .headers(manage)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"url": "http://receiver.internal.test/hooks", \
                                "description": "Contract test", \
                                "enabledEvents": ["payment.captured"], \
                                "metadata": {"team": "payments"}}"""))
                .andReturn();
        String endpointId = idOf(created.getResponse().getContentAsString());

        // Deleted last, so every read above still has an endpoint to find.
        return List.of(
                ContractCall.post("/v1/webhook_endpoints", manage,
                        """
                        {"url": "http://receiver.internal.test/hooks-2", \
                        "enabledEvents": ["*"]}""", 201),
                ContractCall.post("/v1/webhook_endpoints", manage,
                        // The documented 400: an endpoint subscribed to nothing.
                        "{\"url\": \"http://receiver.internal.test/hooks-3\", \"enabledEvents\": []}", 400),
                ContractCall.get("/v1/webhook_endpoints", manage, 200),
                ContractCall.get("/v1/webhook_endpoints/" + endpointId, manage, 200),
                ContractCall.get("/v1/webhook_endpoints/" + UUID.randomUUID(), manage, 404),
                ContractCall.patch("/v1/webhook_endpoints/" + endpointId, manage,
                        "{\"description\": \"Renamed by the contract test\", \"enabled\": false}", 200),
                ContractCall.post("/v1/webhook_endpoints/" + endpointId + "/rotate_secret", manage, null, 200),
                ContractCall.get("/v1/webhook_deliveries", manage, 200),
                ContractCall.delete("/v1/webhook_endpoints/" + endpointId, manage, 204));
    }

    /** The created endpoint's id, read out of the wrapper that also carried the secret. */
    private static String idOf(String body) {
        String marker = "\"id\":\"";
        int start = body.indexOf(marker) + marker.length();
        return body.substring(start, body.indexOf('"', start));
    }
}
