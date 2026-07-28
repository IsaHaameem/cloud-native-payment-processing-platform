package com.paymentflow.payment;

import com.paymentflow.payment.config.OpenApiConfig;
import com.paymentflow.openapi.OpenApiFragments;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
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
 * The OpenAPI description of payment-service's public surface (M21.1).
 *
 * <p><b>What this asserts and why it is not a smoke test.</b> The document is generated,
 * so the interesting failures are not "is it produced" but "does it describe the right
 * API". Two of those are contractual rather than cosmetic:
 *
 * <ul>
 *   <li><b>Only {@code /v1} appears.</b> §9.5 excludes {@code /api/v1} and
 *       {@code /internal/v1} from the published spec deliberately — documenting them
 *       "would imply a promise the platform does not intend to make". That exclusion is
 *       one line of YAML today ({@code springdoc.paths-to-match}), and a line of YAML
 *       nobody re-reads is exactly how the dashboard tier ends up in an SDK two
 *       milestones from now. Asserted in both directions: nothing internal leaks in, and
 *       nothing public silently drops out.</li>
 *   <li><b>It is OpenAPI 3.1.</b> springdoc emits 3.0.1 by default. Everything M21
 *       promises downstream — JSON Schema 2020-12 alignment, honest nullability, the
 *       {@code examples} keyword — is 3.1 behaviour, and the version string is the only
 *       thing a generator reads to decide which dialect it is parsing.</li>
 * </ul>
 *
 * <p>Runs against the real application context rather than a slice, because what is being
 * proven includes the security chain's treatment of the document endpoint: springdoc
 * mapping the route is not the same fact as the route being reachable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class OpenApiDocumentIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:8-alpine"))
            .withExposedPorts(6379);

    /**
     * Every operation the public tier serves, as {@link com.paymentflow.payment.web.PaymentV1Controller}
     * declares them. Spelled out rather than reflected off the controller: a test that
     * derives its expectation from the same source as the thing it checks would pass
     * however the mappings changed, which is the opposite of what a contract test is for.
     */
    private static final Set<String> EXPECTED_PATHS = Set.of(
            "/v1/payments",
            "/v1/payments/{id}",
            "/v1/payments/{id}/authorize",
            "/v1/payments/{id}/capture",
            "/v1/payments/{id}/refund",
            "/v1/payments/{id}/void",
            "/v1/refunds",
            "/v1/refunds/{id}");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private static JsonNode document;
    /** The bytes the service served, kept so the fragment is written verbatim (M21.3). */
    private static String documentJson;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        // Neither is reached: generating the document calls no collaborator. Pointed at a
        // closed port for the same reason the other integration tests do — so a
        // regression that made it call one fails loudly instead of hanging.
        registry.add("paymentflow.services.identity.jwks-uri", () -> "http://localhost:1/oauth2/jwks");
        registry.add("paymentflow.services.merchant.base-uri", () -> "http://localhost:1");
    }

    /**
     * Fetched once per class and cached — static, because JUnit builds a fresh test
     * instance per method. Every assertion below reads one document, which is also the
     * point: they are describing a single artefact, not eleven independent fetches.
     */
    private JsonNode document() throws Exception {
        if (document == null) {
            // No credential of any kind on this request — see SecurityConfig. If the
            // endpoint ever stops being permitted, this fails here rather than in CI's
            // spec-diff job with a 401 body that does not parse as OpenAPI.
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
        // springdoc's default is "3.0.1"; the whole point of the api-docs.version setting
        // is this string, and every downstream tool branches on it.
        assertThat(document().path("openapi").asString()).startsWith("3.1");
    }

    @Test
    void everyDocumentedPathIsPublicAndEveryPublicPathIsDocumented() throws Exception {
        List<String> paths = List.copyOf(document().path("paths").propertyNames());

        assertThat(paths)
                .describedAs("the published spec describes exactly the public /v1 tier")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_PATHS);
    }

    @Test
    void theInternalTiersAreAbsentRatherThanMerelyUnlisted() throws Exception {
        List<String> paths = List.copyOf(document().path("paths").propertyNames());

        // The two that would actually appear if the filter were dropped:
        // PaymentController's dashboard tier, and the actuator endpoints.
        assertThat(paths).noneMatch(path -> path.startsWith("/api/v1"));
        assertThat(paths).noneMatch(path -> path.startsWith("/internal/"));
        assertThat(paths).noneMatch(path -> path.startsWith("/actuator"));
        // `/error` is Spring's own handler, not an endpoint anyone integrates against;
        // springdoc documents it whenever the path filter is loose enough to admit it.
        assertThat(paths).doesNotContain("/error");
    }

    @Test
    void theMutatingOperationsAreDocumentedAlongsideTheReadOnes() throws Exception {
        JsonNode paths = document().path("paths");

        // A filter expressed as a path prefix says nothing about verbs, and the public
        // tier is not read-only — /v1/payments serves both POST and GET.
        assertThat(paths.path("/v1/payments").propertyNames()).containsExactlyInAnyOrder("get", "post");
        assertThat(paths.path("/v1/payments/{id}/refund").propertyNames()).containsExactly("post");
        assertThat(paths.path("/v1/refunds/{id}").propertyNames()).containsExactly("get");
    }

    @Test
    void theDocumentNamesTheContractVersionRatherThanTheJarVersion() throws Exception {
        JsonNode info = document().path("info");

        assertThat(info.path("title").asString()).isEqualTo("PaymentFlow API");
        // The date-based public API revision (§5/M21), not "0.0.1-SNAPSHOT" — which is
        // springdoc's default and means nothing to an integrator.
        assertThat(info.path("version").asString()).isEqualTo(OpenApiConfig.API_VERSION);
        assertThat(info.path("description").asString()).isNotEmpty();
    }

    @Test
    void theServerIsThePublicEdgeAndNotThisServicesOwnPort() throws Exception {
        JsonNode servers = document().path("servers");

        // springdoc infers the server from the incoming request unless told otherwise,
        // which would publish the test's own host here and localhost:8083 in production
        // — a spec that generates SDKs pointed at a host no merchant can reach.
        assertThat(servers.size()).isEqualTo(1);
        assertThat(servers.get(0).path("url").asString()).isEqualTo("https://api.paymentflow.dev");
    }

    @Test
    void everyOperationRequiresASecretKey() throws Exception {
        JsonNode scheme = document().path("components").path("securitySchemes").path("SecretKey");

        assertThat(scheme.path("type").asString()).isEqualTo("http");
        assertThat(scheme.path("scheme").asString()).isEqualTo("bearer");
        // Deliberately not "JWT": the value is an opaque sk_ key and saying otherwise
        // invites an SDK author to decode it.
        assertThat(scheme.path("bearerFormat").asString()).isEqualTo("sk");

        // Declared once at the document level rather than per operation, so a new
        // endpoint cannot be added without it.
        JsonNode security = document().path("security");
        assertThat(security.size()).isEqualTo(1);
        assertThat(security.get(0).propertyNames()).containsExactly("SecretKey");
    }

    @Test
    void theListsPublishTheMetadataFilterAndNotTheMapSpringBindsItInto() throws Exception {
        for (String path : List.of("/v1/payments", "/v1/refunds")) {
            JsonNode parameters = document().path("paths").path(path).path("get").path("parameters");
            List<String> names = parameters.valueStream().map(p -> p.path("name").asString()).toList();

            assertThat(names)
                    .describedAs("%s publishes the filter merchants send", path)
                    .contains("metadata")
                    // The handler's own argument. Published verbatim it would appear as a
                    // *required* object parameter that does not exist on the wire, which a
                    // generator turns into a mandatory SDK argument nobody can supply.
                    .doesNotContain("requestParams");

            JsonNode metadata = parameters.valueStream()
                    .filter(p -> "metadata".equals(p.path("name").asString()))
                    .findFirst().orElseThrow();
            // Defaulted rather than asserted present: OpenAPI's own default for `required`
            // is false, so springdoc omits the key, and demanding it would assert the
            // serializer's verbosity rather than the contract.
            assertThat(metadata.path("required").asBoolean(false)).isFalse();
            // deepObject + explode is precisely `metadata[key]=value`, repeated — the
            // spelling docs/READ_APIS.md publishes and MetadataFilterParams parses.
            assertThat(metadata.path("style").asString()).isEqualTo("deepObject");
            assertThat(metadata.path("explode").asBoolean(false)).isTrue();
            assertThat(metadata.path("description").asString()).contains("metadata[key]=value");
        }
    }

    @Test
    void responsesAreTypedAsJsonRatherThanAnythingAtAll() throws Exception {
        JsonNode content = document().path("paths").path("/v1/payments").path("get")
                .path("responses").path("200").path("content");

        // springdoc's default is `*/*` when a handler declares no `produces`, and none
        // here do. An SDK author reading that has to guess the Accept header.
        assertThat(content.propertyNames()).containsExactly("application/json");
    }

    @Test
    void theTagsAreResourceNamesRatherThanTheJavaClassName() throws Exception {
        JsonNode paths = document().path("paths");

        // Left to springdoc this reads `payment-v-1-controller` — an implementation
        // detail that would then name a section of the docs site and a group of SDK
        // methods.
        assertThat(tagsOf(paths.path("/v1/payments").path("post"))).containsExactly("Payments");
        // Exactly one, not two: a method-level @Tag adds to the class's tags rather than
        // replacing them, so the refund operations came out under Payments as well.
        assertThat(tagsOf(paths.path("/v1/refunds").path("get"))).containsExactly("Refunds");
        assertThat(tagsOf(paths.path("/v1/refunds/{id}").path("get"))).containsExactly("Refunds");
    }

    @Test
    void everyTagUsedByAnOperationIsDeclaredAndDescribed() throws Exception {
        JsonNode declared = document().path("tags");
        List<String> declaredNames = declared.valueStream().map(t -> t.path("name").asString()).toList();

        assertThat(declaredNames).containsExactly("Payments", "Refunds");
        assertThat(declared.valueStream().map(t -> t.path("description").asString()).toList())
                .allSatisfy(description -> assertThat(description).isNotEmpty());

        // The cross-check, and the reason the two spellings can safely live apart: the
        // names are literals in OpenApiConfig and constants on the controller. An
        // operation tagged with a name the document never declares renders as an unnamed,
        // undescribed section on the docs site — valid OpenAPI, useless output.
        List<String> used = document().path("paths").valueStream()
                .flatMap(JsonNode::valueStream)
                .flatMap(operation -> tagsOf(operation).stream())
                .distinct()
                .toList();
        assertThat(used).isNotEmpty().allSatisfy(tag -> assertThat(declaredNames).contains(tag));
    }

    @Test
    void everyOperationDocumentsTheStandardErrorResponses() throws Exception {
        JsonNode paths = document().path("paths");

        // M21.4. Applied from one customizer in common-lib rather than annotated 124 times,
        // so what is worth asserting is the outcome: no operation is missing them. An SDK
        // generated from a document where one operation forgot its 401 has no error type
        // for that call, and nothing about the document would look wrong.
        paths.propertyNames().forEach(path ->
                paths.path(path).propertyNames().forEach(verb -> {
                    JsonNode responses = paths.path(path).path(verb).path("responses");
                    assertThat(List.copyOf(responses.propertyNames()))
                            .describedAs("%s %s documents the standard errors", verb, path)
                            .contains("401", "403", "429", "500");
                }));
    }

    @Test
    void theErrorResponsesReferenceApiErrorAndShowARealBody() throws Exception {
        JsonNode unauthorized = document().path("paths").path("/v1/payments").path("get")
                .path("responses").path("401").path("content").path("application/json");

        assertThat(unauthorized.path("schema").path("$ref").asString())
                .isEqualTo("#/components/schemas/ApiError");
        // §9.2: show the response, not only its schema. The example is where a reader learns
        // that `type` is the field to branch on and `requestId` is the one to quote.
        assertThat(unauthorized.path("example").path("type").asString()).isEqualTo("authentication_error");
        assertThat(unauthorized.path("example").path("code").asString()).isEqualTo("UNAUTHORIZED");
        assertThat(unauthorized.path("example").path("requestId").asString()).isNotEmpty();
        assertThat(unauthorized.path("example").path("docUrl").asString())
                .startsWith("https://docs.paymentflow.dev/errors#");
    }

    @Test
    void theApiErrorSchemaIsGeneratedRatherThanLeftAsADanglingRef() throws Exception {
        // Nothing returns ApiError from a handler signature, so springdoc would never
        // generate it on its own — the document would carry references to a schema that is
        // not in it. Registered explicitly by PublicApiDocument.errorSchemaCustomizer().
        JsonNode apiError = document().path("components").path("schemas").path("ApiError");

        assertThat(List.copyOf(apiError.path("properties").propertyNames()))
                .contains("status", "type", "code", "message", "path", "requestId", "correlationId", "docUrl");
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
        Path fragment = OpenApiFragments.write("payment-service", documentJson);

        assertThat(Files.readString(fragment, StandardCharsets.UTF_8)).isEqualTo(documentJson);
    }

    private static List<String> tagsOf(JsonNode operation) {
        return operation.path("tags").valueStream().map(JsonNode::asString).toList();
    }

    @Test
    void theResourceSchemasAreGeneratedFromTheDtosRatherThanLeftAsBareObjects() throws Exception {
        JsonNode schemas = document().path("components").path("schemas");

        // The document is only worth publishing if the bodies are described. These three
        // are the resources the public tier exchanges; a generator that finds them
        // untyped produces an SDK of Maps.
        assertThat(schemas.path("PaymentResponse").path("properties").propertyNames())
                .contains("id", "object", "amountMinor", "currency", "status", "metadata");
        assertThat(schemas.path("RefundResponse").path("properties").propertyNames())
                .contains("id", "object", "paymentId", "amountMinor", "status");
        assertThat(schemas.path("CreatePaymentRequest").path("properties").propertyNames())
                .contains("amountMinor", "currency", "description", "paymentMethodToken", "metadata");
    }

    @Test
    void theDocumentIsAlsoServedAsYamlForTheMergeStep() throws Exception {
        // M21's merge task and the committed `openapi.yaml` baseline both want YAML.
        // springdoc serves it from a sibling path, which is inside the pattern
        // SecurityConfig permits — asserted because "/v3/api-docs" alone would not have
        // covered it, and the failure would only show up in CI.
        mockMvc.perform(get("/v3/api-docs.yaml"))
                .andExpect(status().isOk());
    }
}
