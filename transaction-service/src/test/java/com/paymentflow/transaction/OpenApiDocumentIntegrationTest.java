package com.paymentflow.transaction;

import com.paymentflow.common.openapi.PublicApiDocument;
import com.paymentflow.openapi.OpenApiFragments;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The OpenAPI description of transaction-service's public surface (M21.2).
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
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@AutoConfigureMockMvc
@Testcontainers
class OpenApiDocumentIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    /**
     * Every path the public tier serves, as {@code BalanceController} declares them.
     * Spelled out rather than reflected off the controller: a test that derives its
     * expectation from the same source as the thing it checks would pass however the
     * mappings changed.
     */
    private static final Set<String> EXPECTED_PATHS = Set.of(
            "/v1/balance",
            "/v1/balance_transactions");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private static JsonNode document;
    /** The bytes the service served, kept so the fragment is written verbatim (M21.3). */
    private static String documentJson;

    /** Fetched once per class and cached; every assertion describes one artefact. */
    private JsonNode document() throws Exception {
        if (document == null) {
            // No credential of any kind on this request (D148). If the endpoint ever stops
            // being permitted, this fails here rather than in CI's spec-diff job with a 401
            // body that does not parse as OpenAPI.
            String body = mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            documentJson = body;
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
    void theTagsAreResourceNamesRatherThanTheJavaClassName() throws Exception {
        JsonNode paths = document().path("paths");

        // Left to springdoc this reads `balance-controller` — an implementation detail
        // that would then name a section of the docs site (M25) and a group of SDK
        // methods (M22). Exactly one tag each, not two: a class-level @Tag is *added* to
        // every operation rather than overridden (M21.1), which would file both of these
        // under both resources.
        assertThat(tagsOf(paths.path("/v1/balance").path("get"))).containsExactly("Balance");
        assertThat(tagsOf(paths.path("/v1/balance_transactions").path("get")))
                .containsExactly("Balance transactions");
    }

    @Test
    void everyTagUsedByAnOperationIsDeclaredAndDescribed() throws Exception {
        JsonNode declared = document().path("tags");
        List<String> declaredNames = declared.valueStream().map(t -> t.path("name").asString()).toList();

        assertThat(declaredNames).containsExactly("Balance", "Balance transactions");
        assertThat(declared.valueStream().map(t -> t.path("description").asString()).toList())
                .allSatisfy(description -> assertThat(description).isNotEmpty());

        // The cross-check that lets the two spellings live apart: an operation tagged with
        // a name the document never declares renders as an unnamed, undescribed section on
        // the docs site — valid OpenAPI, useless output.
        assertThat(usedTags()).isNotEmpty()
                .allSatisfy(tag -> assertThat(declaredNames).contains(tag));
    }

    @Test
    void theResourceSchemasAreGeneratedFromTheDtosRatherThanLeftAsBareObjects() throws Exception {
        JsonNode schemas = document().path("components").path("schemas");

        // The document is only worth publishing if the bodies are described; a generator
        // that finds them untyped produces an SDK of Maps.
        assertThat(schemas.path("BalanceResponse").path("properties").propertyNames()).isNotEmpty();
        assertThat(schemas.path("BalanceTransactionResponse").path("properties").propertyNames())
                .contains("id", "amountMinor", "currency");
    }

    @Test
    void theCursorPagedListPublishesItsPaginationParameters() throws Exception {
        JsonNode parameters = document().path("paths").path("/v1/balance_transactions")
                .path("get").path("parameters");
        List<String> names = parameters.valueStream().map(p -> p.path("name").asString()).toList();

        // The wire spelling (snake_case), not the Java argument names — these are what a
        // caller actually sends, and M19's cursor contract is only usable if they appear.
        assertThat(names).contains("limit", "starting_after", "created_after", "created_before");
        // Every one of them is optional; a generator that saw otherwise would emit an SDK
        // demanding a cursor on the first call, when there is nothing to page from yet.
        assertThat(parameters.valueStream().map(p -> p.path("required").asBoolean(false)).toList())
                .allSatisfy(required -> assertThat(required).isFalse());
    }

    @Test
    void theDocumentIsAlsoServedAsYamlForTheMergeStep() throws Exception {
        // M21.3's merge task and the committed openapi.yaml baseline both want YAML.
        // springdoc serves it from a *sibling* path, so "/v3/api-docs/**" would not have
        // covered it — the failure would only show up in CI.
        mockMvc.perform(get("/v3/api-docs.yaml")).andExpect(status().isOk());
    }

    /**
     * Writes this service's fragment where {@code mergeOpenApi} will find it (M21.3).
     *
     * <p>Less an assertion than a by-product, and deliberately placed here rather than in a
     * task of its own: this class is the only place the document exists after a real
     * application context has produced it, and every other test in this file is a
     * precondition for the fragment being worth merging. A fragment generated somewhere
     * that had not asserted the path set, the tier exclusion and the shared contract would
     * be a second, unchecked source of the published API.
     *
     * <p>The bytes written are the ones the service served, compared back rather than
     * assumed: the merged baseline should describe what the API actually returns, and a
     * re-serialization could differ in key order or whitespace without anyone noticing.
     */
    @Test
    void theFragmentIsWrittenForTheMergeStep() throws Exception {
        document();
        Path fragment = OpenApiFragments.write("transaction-service", documentJson);

        assertThat(Files.readString(fragment, StandardCharsets.UTF_8)).isEqualTo(documentJson);
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
