package com.paymentflow.sandbox;

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
 * The OpenAPI description of sandbox-service's public surface (M21.2).
 *
 * <p>The shared half of the contract is proven by {@code PublicApiDocumentTest} in
 * common-lib, where the single implementation lives. What is asserted here is what only
 * this service can know — and this fragment carries the platform's one genuine exception
 * to the document-level security requirement, which is the assertion that matters most:
 * {@code GET /v1/test/cards} is unauthenticated reference data (§8.1), and a document that
 * described it as needing a key would send SDK users looking for a credential to read the
 * catalogue that tells them which credentials to test with.
 */
@SpringBootTest
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
            "/v1/test/cards",
            "/v1/test/simulations",
            "/v1/test/simulations/active",
            "/v1/test/decisions",
            "/v1/test/decisions/payments/{paymentId}");

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
            // No credential of any kind on this request (D148).
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
    void theInternalSandboxDecisionApiIsAbsentRatherThanMerelyUnlisted() throws Exception {
        List<String> paths = List.copyOf(document().path("paths").propertyNames());

        // This service's internal tier is the one payment-service calls to authorize
        // (`/internal/v1/sandbox/decisions`, D103). Publishing it would advertise the
        // acquirer boundary as though a merchant could call it.
        assertThat(paths).noneMatch(path -> path.startsWith("/internal/"));
        assertThat(paths).noneMatch(path -> path.startsWith("/api/v1"));
        assertThat(paths).noneMatch(path -> path.startsWith("/actuator"));
        assertThat(paths).doesNotContain("/error");
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
    void theTestCardCatalogueDeclaresItselfUnauthenticatedRatherThanInheritingTheKeyRequirement()
            throws Exception {
        JsonNode cards = document().path("paths").path("/v1/test/cards").path("get");

        // `security: []` on the operation means "no security" in OpenAPI. An *omitted*
        // security key would inherit the document's SecretKey requirement instead, which
        // is the whole difference: the gateway genuinely permits this route without a key
        // (§8.1), so inheriting would publish a document that contradicts the running
        // system.
        assertThat(cards.has("security"))
                .describedAs("the unauthenticated endpoint states so explicitly")
                .isTrue();
        assertThat(cards.path("security").size()).isZero();
    }

    @Test
    void everyOtherOperationStillRequiresAKey() throws Exception {
        JsonNode paths = document().path("paths");

        // The exception is exactly one endpoint. Every other operation inherits the
        // document-level requirement by *not* declaring its own — asserted so that a
        // future copy-paste of the @SecurityRequirements annotation onto a merchant-scoped
        // endpoint fails here rather than silently publishing it as open.
        for (String path : List.of("/v1/test/simulations", "/v1/test/simulations/active",
                "/v1/test/decisions", "/v1/test/decisions/payments/{paymentId}")) {
            paths.path(path).valueStream().forEach(operation ->
                    assertThat(operation.has("security"))
                            .describedAs("%s inherits the document-level SecretKey requirement", path)
                            .isFalse());
        }
    }

    @Test
    void theMutatingSimulationControlsAreDocumentedAlongsideTheReads() throws Exception {
        JsonNode paths = document().path("paths");

        // A path-prefix filter says nothing about verbs, and this tier is how a developer
        // forces a failure path on demand — an SDK missing POST or DELETE here would make
        // the sandbox read-only.
        assertThat(paths.path("/v1/test/simulations").propertyNames()).containsExactly("post");
        assertThat(paths.path("/v1/test/simulations/active").propertyNames())
                .containsExactlyInAnyOrder("get", "delete");
        assertThat(paths.path("/v1/test/cards").propertyNames()).containsExactly("get");
    }

    @Test
    void theTagsAreResourceNamesRatherThanTheJavaClassName() throws Exception {
        JsonNode paths = document().path("paths");

        // Left to springdoc these read `test-card-controller`, `simulation-controller`,
        // `decision-log-controller` — implementation details that would name the docs
        // site's sections (M25) and the SDKs' method groups (M22).
        assertThat(tagsOf(paths.path("/v1/test/cards").path("get"))).containsExactly("Test cards");
        assertThat(tagsOf(paths.path("/v1/test/simulations").path("post"))).containsExactly("Simulations");
        assertThat(tagsOf(paths.path("/v1/test/decisions").path("get"))).containsExactly("Decisions");
    }

    @Test
    void everyTagUsedByAnOperationIsDeclaredAndDescribed() throws Exception {
        JsonNode declared = document().path("tags");
        List<String> declaredNames = declared.valueStream().map(t -> t.path("name").asString()).toList();

        assertThat(declaredNames).containsExactly("Test cards", "Simulations", "Decisions");
        assertThat(declared.valueStream().map(t -> t.path("description").asString()).toList())
                .allSatisfy(description -> assertThat(description).isNotEmpty());

        assertThat(usedTags()).isNotEmpty()
                .allSatisfy(tag -> assertThat(declaredNames).contains(tag));
    }

    @Test
    void theResourceSchemasAreGeneratedFromTheDtosRatherThanLeftAsBareObjects() throws Exception {
        JsonNode schemas = document().path("components").path("schemas");

        assertThat(schemas.path("TestCardResponse").path("properties").propertyNames()).isNotEmpty();
        assertThat(schemas.path("SimulationOverrideResponse").path("properties").propertyNames())
                .isNotEmpty();
        assertThat(schemas.path("CreateSimulationOverrideRequest").path("properties").propertyNames())
                .isNotEmpty();
    }

    @Test
    void theOffsetPagedDecisionLogPublishesPageAndSizeRatherThanTheJavaPageableArgument()
            throws Exception {
        List<String> names = document().path("paths").path("/v1/test/decisions").path("get")
                .path("parameters").valueStream().map(p -> p.path("name").asString()).toList();

        // Same hazard as notification-service's delivery list: `Pageable` is a Spring
        // binding type, and published verbatim it becomes a `pageable` object argument no
        // caller can send. @ParameterObject expands it into the real query parameters.
        assertThat(names).contains("page", "size");
        assertThat(names).doesNotContain("pageable");
    }

    @Test
    void theDocumentIsAlsoServedAsYamlForTheMergeStep() throws Exception {
        mockMvc.perform(get("/v3/api-docs.yaml")).andExpect(status().isOk());
    }

    @Test
    void theUnauthenticatedEndpointIsNotDocumentedAsReturning401Or403() throws Exception {
        // The counterpart to the security assertion above (M21.4). `GET /v1/test/cards`
        // needs no credential, so it cannot fail to authenticate — documenting a 401 on it
        // would contradict the running system and make an SDK generate credential handling
        // for a call that takes none. Every other operation here still declares both.
        List<String> cardResponses =
                List.copyOf(document().path("paths").path("/v1/test/cards").path("get")
                        .path("responses").propertyNames());

        assertThat(cardResponses).doesNotContain("401", "403");
        assertThat(cardResponses).contains("429", "500");

        List<String> simulationResponses =
                List.copyOf(document().path("paths").path("/v1/test/simulations").path("post")
                        .path("responses").propertyNames());
        assertThat(simulationResponses).contains("401", "403");
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
        Path fragment = OpenApiFragments.write("sandbox-service", documentJson);

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
