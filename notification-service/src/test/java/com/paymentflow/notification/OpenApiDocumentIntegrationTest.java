package com.paymentflow.notification;

import com.paymentflow.common.openapi.PublicApiDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The OpenAPI description of notification-service's public surface (M21.2).
 *
 * <p>The shared half of the contract — that the version, server and security scheme are
 * the same ones every other service publishes — is proven by {@code PublicApiDocumentTest}
 * in common-lib, where the single implementation lives. What is asserted here is what only
 * this service can know: that the document describes <em>its</em> endpoints, all of them,
 * and nothing else.
 *
 * <p>This is the largest fragment and the one whose public tier is most heavily mutating,
 * so it is also where a path-prefix filter is least sufficient on its own: four of its nine
 * operations change state, and a filter expressed as a path prefix says nothing about
 * verbs.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "paymentflow.webhooks.require-https=false"
})
@AutoConfigureMockMvc
@Testcontainers
class OpenApiDocumentIntegrationTest {

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

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private static JsonNode document;

    /** Fetched once per class and cached; every assertion describes one artefact. */
    private JsonNode document() throws Exception {
        if (document == null) {
            // No credential of any kind on this request (D148). If the endpoint ever stops
            // being permitted, this fails here rather than in CI's spec-diff job with a 401
            // body that does not parse as OpenAPI.
            String body = mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            document = objectMapper.readTree(body);
        }
        return document;
    }

    @Test
    void theDocumentIsOpenApi31() throws Exception {
        assertThat(document().path("openapi").asString()).startsWith("3.1");
    }

    @Test
    void everyDocumentedPathIsPublicAndEveryPublicPathIsDocumented() throws Exception {
        List<String> paths = List.copyOf(document().path("paths").propertyNames());

        assertThat(paths)
                .describedAs("the published spec describes exactly this service's public /v1 tier")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_PATHS);
    }

    @Test
    void theInternalTiersAreAbsentRatherThanMerelyUnlisted() throws Exception {
        List<String> paths = List.copyOf(document().path("paths").propertyNames());

        assertThat(paths).noneMatch(path -> path.startsWith("/api/v1"));
        assertThat(paths).noneMatch(path -> path.startsWith("/internal/"));
        assertThat(paths).noneMatch(path -> path.startsWith("/actuator"));
        assertThat(paths).doesNotContain("/error");
    }

    @Test
    void everyMutatingVerbIsDocumentedAlongsideTheReadOnes() throws Exception {
        JsonNode paths = document().path("paths");

        // A filter expressed as a path prefix says nothing about verbs, and this tier is
        // the platform's only public management API — an SDK missing PATCH or DELETE here
        // would leave a merchant unable to change or remove an endpoint they registered.
        assertThat(paths.path("/v1/webhook_endpoints").propertyNames())
                .containsExactlyInAnyOrder("get", "post");
        assertThat(paths.path("/v1/webhook_endpoints/{id}").propertyNames())
                .containsExactlyInAnyOrder("get", "patch", "delete");
        assertThat(paths.path("/v1/webhook_endpoints/{id}/rotate_secret").propertyNames())
                .containsExactly("post");
        assertThat(paths.path("/v1/webhook_deliveries/{id}/replay").propertyNames())
                .containsExactly("post");
    }

    @Test
    void theDocumentCarriesTheSharedContractRatherThanItsOwn() throws Exception {
        JsonNode info = document().path("info");

        assertThat(info.path("title").asString()).isEqualTo(PublicApiDocument.TITLE);
        assertThat(info.path("version").asString()).isEqualTo(PublicApiDocument.API_VERSION);

        JsonNode servers = document().path("servers");
        assertThat(servers.size()).isEqualTo(1);
        assertThat(servers.get(0).path("url").asString()).isEqualTo(PublicApiDocument.PUBLIC_SERVER_URL);

        JsonNode scheme = document().path("components").path("securitySchemes").path("SecretKey");
        assertThat(scheme.path("type").asString()).isEqualTo("http");
        assertThat(scheme.path("bearerFormat").asString()).isEqualTo("sk");
        assertThat(document().path("security").get(0).propertyNames()).containsExactly("SecretKey");
    }

    @Test
    void requestAndResponseBodiesAreTypedAsJson() throws Exception {
        // springdoc's default is `*/*` when a handler declares no `produces`, and none
        // here do. An SDK author reading that has to guess the Accept header.
        assertThat(document().path("paths").path("/v1/webhook_endpoints").path("get")
                .path("responses").path("200").path("content").propertyNames())
                .containsExactly("application/json");
        assertThat(document().path("paths").path("/v1/webhook_endpoints").path("post")
                .path("requestBody").path("content").propertyNames())
                .containsExactly("application/json");
    }

    @Test
    void theTagsAreResourceNamesRatherThanTheJavaClassName() throws Exception {
        JsonNode paths = document().path("paths");

        // Left to springdoc these read `webhook-endpoint-controller` and
        // `webhook-delivery-controller` — implementation details that would name the docs
        // site's sections (M25) and the SDKs' method groups (M22).
        assertThat(tagsOf(paths.path("/v1/webhook_endpoints").path("post")))
                .containsExactly("Webhook endpoints");
        assertThat(tagsOf(paths.path("/v1/webhook_endpoints/{id}").path("delete")))
                .containsExactly("Webhook endpoints");
        assertThat(tagsOf(paths.path("/v1/webhook_deliveries").path("get")))
                .containsExactly("Webhook deliveries");
        assertThat(tagsOf(paths.path("/v1/webhook_deliveries/{id}/replay").path("post")))
                .containsExactly("Webhook deliveries");
    }

    @Test
    void everyTagUsedByAnOperationIsDeclaredAndDescribed() throws Exception {
        JsonNode declared = document().path("tags");
        List<String> declaredNames = declared.valueStream().map(t -> t.path("name").asString()).toList();

        assertThat(declaredNames).containsExactly("Webhook endpoints", "Webhook deliveries");
        assertThat(declared.valueStream().map(t -> t.path("description").asString()).toList())
                .allSatisfy(description -> assertThat(description).isNotEmpty());

        assertThat(usedTags()).isNotEmpty()
                .allSatisfy(tag -> assertThat(declaredNames).contains(tag));
    }

    @Test
    void theResourceSchemasAreGeneratedFromTheDtosRatherThanLeftAsBareObjects() throws Exception {
        JsonNode schemas = document().path("components").path("schemas");

        assertThat(schemas.path("WebhookEndpointResponse").path("properties").propertyNames())
                .contains("id", "url", "enabled", "enabledEvents", "signingSecretPrefix");
        // Deliberately not asserting an `object` discriminator here, and the omission is
        // the finding rather than an oversight: every other public resource on this
        // platform carries one (`payment`, `refund`, `event`, `balance_transaction`,
        // `request_log`), and the two webhook resources do not. Generating the document is
        // what made that visible. Recorded in §14 as a contract gap for M21.3/M21.4 to
        // close — adding a field to a shipped public response is an API change, not a
        // documentation one, and M21.2 does not make it silently.
        // The create response is the one that carries a raw `whsec_` secret, and it is
        // unrecoverable afterwards — an SDK that did not model it would silently discard
        // the only copy.
        assertThat(schemas.path("WebhookEndpointCreatedResponse").path("properties").propertyNames())
                .isNotEmpty();
        assertThat(schemas.path("CreateWebhookEndpointRequest").path("properties").propertyNames())
                .contains("url", "enabledEvents");
    }

    @Test
    void theOffsetPagedListPublishesPageAndSizeRatherThanTheJavaPageableArgument() throws Exception {
        JsonNode parameters = document().path("paths").path("/v1/webhook_deliveries")
                .path("get").path("parameters");
        List<String> names = parameters.valueStream().map(p -> p.path("name").asString()).toList();

        // This list still uses V1's offset `PageResponse` deliberately (M18: cursors
        // arrived in M19 and were not retrofitted). The hazard is the same one M21.1 found
        // with the metadata Map: `Pageable` is a Spring binding type, not a wire
        // parameter, and published verbatim it becomes a `pageable` object argument no
        // caller can send. @ParameterObject on the argument expands it into the query
        // parameters that actually exist; without it the document published exactly one
        // parameter, named `pageable`.
        assertThat(names).contains("page", "size");
        assertThat(names).doesNotContain("pageable");
        assertThat(parameters.valueStream().map(p -> p.path("required").asBoolean(false)).toList())
                .allSatisfy(required -> assertThat(required).isFalse());
    }

    @Test
    void theDocumentIsAlsoServedAsYamlForTheMergeStep() throws Exception {
        mockMvc.perform(get("/v3/api-docs.yaml")).andExpect(status().isOk());
    }

    private List<String> usedTags() throws Exception {
        return document().path("paths").valueStream()
                .flatMap(JsonNode::valueStream)
                .flatMap(operation -> tagsOf(operation).stream())
                .distinct()
                .toList();
    }

    private static List<String> tagsOf(JsonNode operation) {
        return operation.path("tags").valueStream().map(JsonNode::asString).toList();
    }
}
