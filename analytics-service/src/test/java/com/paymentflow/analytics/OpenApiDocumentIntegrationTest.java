package com.paymentflow.analytics;

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
 * The OpenAPI description of analytics-service's public surface (M21.2).
 *
 * <p>The shared half of the contract — that the version, server and security scheme are
 * the same ones every other service publishes — is proven by {@code PublicApiDocumentTest}
 * in common-lib, where the single implementation lives. What is asserted here is what only
 * this service can know: that the document describes <em>its</em> endpoints, all of them,
 * and nothing else.
 *
 * <p>The exclusion of the internal tiers is the assertion with teeth. It is one line of
 * YAML today ({@code springdoc.paths-to-match}), and a line of YAML nobody re-reads is how
 * an internal route ends up in an SDK two milestones from now. Asserted in both
 * directions: nothing internal leaks in, and nothing public silently drops out.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "paymentflow.request-log.partition-initial-delay-ms=3600000",
        "paymentflow.request-log.rollup-initial-delay-ms=3600000",
        "paymentflow.request-log.retention-initial-delay-ms=3600000"
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
            "/v1/analytics/payments",
            "/v1/request_logs",
            "/v1/usage");

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
        // springdoc's default is "3.0.1" whatever its own version; every downstream
        // generator branches on this string.
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
        // Spring's own handler, not an endpoint anyone integrates against; springdoc
        // documents it whenever the path filter is loose enough to admit it.
        assertThat(paths).doesNotContain("/error");
    }

    @Test
    void theDocumentCarriesTheSharedContractRatherThanItsOwn() throws Exception {
        JsonNode info = document().path("info");

        // The integration half of what PublicApiDocumentTest proves in isolation: the
        // fragment this service actually *serves* carries the shared values, so a stray
        // local @OpenAPIDefinition or a second OpenAPI bean would be caught here.
        assertThat(info.path("title").asString()).isEqualTo(PublicApiDocument.TITLE);
        assertThat(info.path("version").asString()).isEqualTo(PublicApiDocument.API_VERSION);

        JsonNode servers = document().path("servers");
        // springdoc infers the server from the incoming request unless told otherwise,
        // which would publish the test's own random port as the host to call.
        assertThat(servers.size()).isEqualTo(1);
        assertThat(servers.get(0).path("url").asString()).isEqualTo(PublicApiDocument.PUBLIC_SERVER_URL);

        JsonNode scheme = document().path("components").path("securitySchemes").path("SecretKey");
        assertThat(scheme.path("type").asString()).isEqualTo("http");
        assertThat(scheme.path("bearerFormat").asString()).isEqualTo("sk");
        assertThat(document().path("security").get(0).propertyNames()).containsExactly("SecretKey");
    }

    @Test
    void responsesAreTypedAsJsonRatherThanAnythingAtAll() throws Exception {
        for (String path : EXPECTED_PATHS) {
            JsonNode content = document().path("paths").path(path).path("get")
                    .path("responses").path("200").path("content");

            // springdoc's default is `*/*` when a handler declares no `produces`, and none
            // here do. An SDK author reading that has to guess the Accept header.
            assertThat(content.propertyNames())
                    .describedAs("%s declares its media type", path)
                    .containsExactly("application/json");
        }
    }

    @Test
    void theThreeResourcesAreTaggedSeparatelyRatherThanLumpedUnderTheService() throws Exception {
        JsonNode paths = document().path("paths");

        // Left to springdoc these read `analytics-controller` and `request-log-controller`
        // — implementation details that would name the docs site's sections (M25) and the
        // SDKs' method groups (M22). Request logs and usage share a controller, which is
        // exactly the case a class-level @Tag gets wrong: springdoc *adds* it to every
        // operation rather than overriding, so both would be filed under both resources.
        assertThat(tagsOf(paths.path("/v1/analytics/payments").path("get"))).containsExactly("Analytics");
        assertThat(tagsOf(paths.path("/v1/request_logs").path("get"))).containsExactly("Request logs");
        assertThat(tagsOf(paths.path("/v1/usage").path("get"))).containsExactly("Usage");
    }

    @Test
    void theResourceSchemasAreGeneratedFromTheDtosRatherThanLeftAsBareObjects() throws Exception {
        JsonNode schemas = document().path("components").path("schemas");

        assertThat(schemas.path("AnalyticsSummaryResponse").path("properties").propertyNames())
                .contains("object", "from", "to", "createdCount", "failedCount");
        assertThat(schemas.path("RequestLogResponse").path("properties").propertyNames())
                .contains("object", "id", "method", "path", "statusCode", "durationMs");
        assertThat(schemas.path("UsageSummaryResponse").path("properties").propertyNames())
                .contains("from", "to", "totalRequests", "buckets");
    }

    @Test
    void theRequestLogListPublishesItsFiltersAndUsageItsDateRange() throws Exception {
        List<String> logParams = document().path("paths").path("/v1/request_logs").path("get")
                .path("parameters").valueStream().map(p -> p.path("name").asString()).toList();
        assertThat(logParams).contains("limit", "starting_after", "created_after", "created_before",
                "status_code", "method");

        JsonNode usage = document().path("paths").path("/v1/usage").path("get").path("parameters");
        assertThat(usage.valueStream().map(p -> p.path("name").asString()).toList())
                .containsExactlyInAnyOrder("from", "to");
        // Dates, not instants — the aggregate is per UTC day, and publishing a date-time
        // here would advertise a precision the stored data does not have.
        JsonNode from = usage.valueStream()
                .filter(p -> "from".equals(p.path("name").asString())).findFirst().orElseThrow();
        assertThat(from.path("schema").path("format").asString()).isEqualTo("date");
    }


    @Test
    void everyTagUsedByAnOperationIsDeclaredAndDescribed() throws Exception {
        JsonNode declared = document().path("tags");
        List<String> declaredNames = declared.valueStream().map(t -> t.path("name").asString()).toList();

        assertThat(declaredNames).containsExactly("Analytics", "Request logs", "Usage");
        assertThat(declared.valueStream().map(t -> t.path("description").asString()).toList())
                .allSatisfy(description -> assertThat(description).isNotEmpty());

        // The cross-check that lets the two spellings live apart: an operation tagged with
        // a name the document never declares renders as an unnamed, undescribed section on
        // the docs site — valid OpenAPI, useless output.
        assertThat(usedTags()).isNotEmpty()
                .allSatisfy(tag -> assertThat(declaredNames).contains(tag));
    }

    @Test
    void theDocumentIsAlsoServedAsYamlForTheMergeStep() throws Exception {
        // M21.3's merge task and the committed openapi.yaml baseline both want YAML.
        // springdoc serves it from a *sibling* path, so "/v3/api-docs/**" would not have
        // covered it — the failure would only show up in CI.
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
