package com.paymentflow.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.paymentflow.testsupport.openapi.PublicApiDocumentContract;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OpenAPI description of payment-service's public surface (M21.1, rebased onto the
 * shared scaffold in M21.7).
 *
 * <p>Everything true of every service's fragment — that it is 3.1, that the internal tiers
 * are absent, that the shared contract is carried, that every operation carries prose and
 * documents the universal errors, that every published field is described — is inherited
 * from {@link PublicApiDocumentContract}, which is where it now lives exactly once instead
 * of six times. What is left here is what only payment-service can know.
 */
@SpringBootTest
@Testcontainers
class OpenApiDocumentIntegrationTest extends PublicApiDocumentContract {

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

    @Override
    protected String serviceName() {
        return "payment-service";
    }

    @Override
    protected Set<String> publicPaths() {
        return EXPECTED_PATHS;
    }

    @Override
    protected List<String> tagNames() {
        return List.of("Payments", "Refunds");
    }

    @Test
    void theMutatingOperationsAreDocumentedAlongsideTheReadOnes() throws Exception {
        JsonNode paths = document().path("paths");

        // A filter expressed as a path prefix says nothing about verbs, and the public
        // tier is not read-only — /v1/payments serves both POST and GET.
        assertThat(names(paths.path("/v1/payments"))).containsExactlyInAnyOrder("get", "post");
        assertThat(names(paths.path("/v1/payments/{id}/refund"))).containsExactly("post");
        assertThat(names(paths.path("/v1/refunds/{id}"))).containsExactly("get");
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
    void theListsPublishTheMetadataFilterAndNotTheMapSpringBindsItInto() throws Exception {
        for (String path : List.of("/v1/payments", "/v1/refunds")) {
            JsonNode parameters = document().path("paths").path(path).path("get").path("parameters");
            List<String> names = new java.util.ArrayList<>();
            parameters.forEach(parameter -> names.add(parameter.path("name").asText()));

            assertThat(names)
                    .describedAs("%s publishes the filter merchants send", path)
                    .contains("metadata")
                    // The handler's own argument. Published verbatim it would appear as a
                    // *required* object parameter that does not exist on the wire, which a
                    // generator turns into a mandatory SDK argument nobody can supply.
                    .doesNotContain("requestParams");

            JsonNode metadata = parameterNamed(parameters, "metadata");
            // Defaulted rather than asserted present: OpenAPI's own default for `required`
            // is false, so springdoc omits the key, and demanding it would assert the
            // serializer's verbosity rather than the contract.
            assertThat(metadata.path("required").asBoolean(false)).isFalse();
            // deepObject + explode is precisely `metadata[key]=value`, repeated — the
            // spelling docs/READ_APIS.md publishes and MetadataFilterParams parses.
            assertThat(metadata.path("style").asText()).isEqualTo("deepObject");
            assertThat(metadata.path("explode").asBoolean(false)).isTrue();
            assertThat(metadata.path("description").asText()).contains("metadata[key]=value");
        }
    }

    @Test
    void theIdempotencyKeyIsPublishedAsARequiredHeaderOnEveryMutatingOperation() throws Exception {
        // M21.7, and corrected by M21.7's own contract test: the document published this as
        // optional, and `POST /v1/payments` without it is a 400. Every mutation on this tier
        // demands it — `PaymentService.requireIdempotencyKey` — and the Spring-level
        // `required = false` exists so that omission produces a catalogued BAD_REQUEST
        // rather than Spring's own unmapped error, not because the header is optional.
        for (String path : List.of("/v1/payments", "/v1/payments/{id}/authorize",
                "/v1/payments/{id}/capture", "/v1/payments/{id}/refund", "/v1/payments/{id}/void")) {
            JsonNode parameters = document().path("paths").path(path).path("post").path("parameters");
            JsonNode key = parameterNamed(parameters, "Idempotency-Key");

            assertThat(key.path("in").asText())
                    .describedAs("%s publishes Idempotency-Key as a header", path)
                    .isEqualTo("header");
            assertThat(key.path("required").asBoolean(false))
                    .describedAs("%s publishes Idempotency-Key as required, because it is", path)
                    .isTrue();
            assertThat(key.path("description").asText()).isNotEmpty();
        }
    }

    @Test
    void theStateTransitionsDocumentTheConflictTheyCanReturn() throws Exception {
        // M21.7, D154's per-operation half. A capture that has already happened is a 409,
        // and it is the single most likely non-2xx an integrator meets on this tier — the
        // universal customizer deliberately cannot know it, because only this service does.
        for (String path : List.of("/v1/payments/{id}/authorize", "/v1/payments/{id}/capture",
                "/v1/payments/{id}/refund", "/v1/payments/{id}/void")) {
            JsonNode responses = document().path("paths").path(path).path("post").path("responses");

            assertThat(names(responses))
                    .describedAs("%s documents its own 404 and 409", path)
                    .contains("404", "409");
            assertThat(responses.path("409").path("content").path("application/json")
                    .path("schema").path("$ref").asText())
                    .isEqualTo("#/components/schemas/ApiError");
        }
    }

    @Test
    void theErrorResponsesReferenceApiErrorAndShowARealBody() throws Exception {
        JsonNode unauthorized = document().path("paths").path("/v1/payments").path("get")
                .path("responses").path("401").path("content").path("application/json");

        assertThat(unauthorized.path("schema").path("$ref").asText())
                .isEqualTo("#/components/schemas/ApiError");
        // §9.2: show the response, not only its schema. The example is where a reader learns
        // that `type` is the field to branch on and `requestId` is the one to quote.
        assertThat(unauthorized.path("example").path("type").asText()).isEqualTo("authentication_error");
        assertThat(unauthorized.path("example").path("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(unauthorized.path("example").path("requestId").asText()).isNotEmpty();
        assertThat(unauthorized.path("example").path("docUrl").asText())
                .startsWith("https://docs.paymentflow.dev/errors#");
    }

    @Test
    void theApiErrorSchemaIsGeneratedRatherThanLeftAsADanglingRef() throws Exception {
        // Nothing returns ApiError from a handler signature, so springdoc would never
        // generate it on its own — the document would carry references to a schema that is
        // not in it. Registered explicitly by PublicApiDocument.sharedSchemaCustomizer().
        JsonNode apiError = document().path("components").path("schemas").path("ApiError");

        assertThat(names(apiError.path("properties")))
                .contains("status", "type", "code", "message", "path", "requestId", "correlationId", "docUrl");
    }

    @Test
    void theResourceSchemasAreGeneratedFromTheDtosRatherThanLeftAsBareObjects() throws Exception {
        JsonNode schemas = document().path("components").path("schemas");

        // The document is only worth publishing if the bodies are described. These three
        // are the resources the public tier exchanges; a generator that finds them
        // untyped produces an SDK of Maps.
        assertThat(names(schemas.path("PaymentResponse").path("properties")))
                .contains("id", "object", "amountMinor", "currency", "status", "metadata");
        assertThat(names(schemas.path("RefundResponse").path("properties")))
                .contains("id", "object", "paymentId", "amountMinor", "status");
        assertThat(names(schemas.path("CreatePaymentRequest").path("properties")))
                .contains("amountMinor", "currency", "description", "paymentMethodToken", "metadata");
    }

    private static JsonNode parameterNamed(JsonNode parameters, String name) {
        for (JsonNode parameter : parameters) {
            if (name.equals(parameter.path("name").asText())) {
                return parameter;
            }
        }
        throw new AssertionError("no parameter named " + name);
    }
}
