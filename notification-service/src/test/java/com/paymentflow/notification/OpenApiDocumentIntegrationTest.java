package com.paymentflow.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.paymentflow.testsupport.openapi.PublicApiDocumentContract;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OpenAPI description of notification-service's public surface (M21.2, rebased onto the
 * shared scaffold in M21.7).
 *
 * <p>The shared half is inherited from {@link PublicApiDocumentContract}. What is left is
 * this service's own, and the assertion that carries the most weight is
 * {@link #theSigningSecretAppearsOnlyWhereItIsActuallyReturned()}: a document that showed
 * the raw {@code whsec_} secret on the ordinary read would tell every SDK author to expect
 * a field that is null on every call but two, and would imply the platform can hand it back
 * — which it genuinely cannot.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "paymentflow.webhooks.require-https=false"
})
@Testcontainers
class OpenApiDocumentIntegrationTest extends PublicApiDocumentContract {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    /**
     * Every path the public tier serves. Spelled out rather than reflected off the
     * controllers: a test that derives its expectation from the same source as the thing
     * it checks would pass however the mappings changed.
     */
    private static final Set<String> EXPECTED_PATHS = Set.of(
            "/v1/webhook_endpoints",
            "/v1/webhook_endpoints/{id}",
            "/v1/webhook_endpoints/{id}/rotate_secret",
            "/v1/webhook_deliveries",
            "/v1/webhook_deliveries/{id}",
            "/v1/webhook_deliveries/{id}/replay");

    @Override
    protected String serviceName() {
        return "notification-service";
    }

    @Override
    protected Set<String> publicPaths() {
        return EXPECTED_PATHS;
    }

    @Override
    protected List<String> tagNames() {
        return List.of("Webhook endpoints", "Webhook deliveries");
    }

    @Test
    void theFullVerbSurfaceIsDocumentedAndNotOnlyTheReads() throws Exception {
        JsonNode paths = document().path("paths");

        // A path-prefix filter says nothing about verbs, and this is the only public
        // resource in the platform a merchant creates, patches and deletes. An SDK missing
        // DELETE here would leave endpoints unremovable.
        assertThat(names(paths.path("/v1/webhook_endpoints")))
                .containsExactlyInAnyOrder("get", "post");
        assertThat(names(paths.path("/v1/webhook_endpoints/{id}")))
                .containsExactlyInAnyOrder("get", "patch", "delete");
        assertThat(names(paths.path("/v1/webhook_endpoints/{id}/rotate_secret")))
                .containsExactly("post");
        assertThat(names(paths.path("/v1/webhook_deliveries/{id}/replay")))
                .containsExactly("post");
    }

    @Test
    void theTagsAreResourceNamesRatherThanTheJavaClassName() throws Exception {
        JsonNode paths = document().path("paths");

        // Left to springdoc these read `webhook-endpoint-controller` and
        // `webhook-delivery-controller`.
        assertThat(tagsOf(paths.path("/v1/webhook_endpoints").path("post")))
                .containsExactly("Webhook endpoints");
        assertThat(tagsOf(paths.path("/v1/webhook_deliveries").path("get")))
                .containsExactly("Webhook deliveries");
    }

    @Test
    void theResourceSchemasCarryTheObjectDiscriminatorLikeEveryOtherResource() throws Exception {
        JsonNode schemas = document().path("components").path("schemas");

        // D150. Both webhook resources lacked `object` until M21.3 — found by generating
        // this document and reading the schemas side by side, which is a fair argument for
        // the document having been worth generating.
        assertThat(names(schemas.path("WebhookEndpointResponse").path("properties")))
                .contains("id", "object", "url", "enabled", "enabledEvents", "metadata");
        assertThat(names(schemas.path("WebhookDeliveryResponse").path("properties")))
                .contains("id", "object", "eventId", "status", "attempts");
    }

    @Test
    void theSigningSecretAppearsOnlyWhereItIsActuallyReturned() throws Exception {
        // M21.7. The raw secret is returned by exactly two operations — registration and
        // rotation — and by nothing else, because only a hash is kept. The read schema
        // publishes a prefix instead, and the distinction has to survive into the document:
        // an SDK that expected `signingSecret` on every read would model a field that is
        // absent on every call but two.
        JsonNode schemas = document().path("components").path("schemas");

        assertThat(names(schemas.path("WebhookEndpointCreatedResponse").path("properties")))
                .containsExactlyInAnyOrder("endpoint", "signingSecret");
        assertThat(names(schemas.path("WebhookEndpointResponse").path("properties")))
                .doesNotContain("signingSecret")
                .contains("signingSecretPrefix");
        assertThat(schemas.path("WebhookEndpointCreatedResponse").path("properties")
                .path("signingSecret").path("description").asText())
                .contains("once");
    }

    @Test
    void theOffsetPagedDeliveryListPublishesPageAndSizeRatherThanTheJavaPageableArgument()
            throws Exception {
        JsonNode parameters = document().path("paths").path("/v1/webhook_deliveries").path("get")
                .path("parameters");
        List<String> names = new ArrayList<>();
        parameters.forEach(parameter -> names.add(parameter.path("name").asText()));

        // `Pageable` is a Spring binding type, and published verbatim it becomes a required
        // `pageable` object argument no caller can send (M21.2). @ParameterObject expands it
        // into the real query parameters.
        assertThat(names).contains("page", "size");
        assertThat(names).doesNotContain("pageable");
    }

    @Test
    void theMutatingOperationsDocumentTheirOwnFailures() throws Exception {
        // M21.7, D154's per-operation half. Registering an endpoint at a URL you already
        // use is a 409 and an unreachable URL is a 400 — neither is knowable by the
        // universal customizer, and both are what an integrator meets first.
        JsonNode create = document().path("paths").path("/v1/webhook_endpoints").path("post")
                .path("responses");
        assertThat(names(create)).contains("400", "409");

        JsonNode replay = document().path("paths").path("/v1/webhook_deliveries/{id}/replay")
                .path("post").path("responses");
        assertThat(names(replay)).contains("404", "409");

        JsonNode delete = document().path("paths").path("/v1/webhook_endpoints/{id}")
                .path("delete").path("responses");
        assertThat(names(delete)).contains("204", "404");
    }
}
