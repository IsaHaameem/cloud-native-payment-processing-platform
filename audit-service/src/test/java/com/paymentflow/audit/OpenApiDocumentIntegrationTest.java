package com.paymentflow.audit;

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
 * The OpenAPI description of audit-service's public surface (M21.2, rebased onto the shared
 * scaffold in M21.7).
 *
 * <p>The shared half is inherited from {@link PublicApiDocumentContract}. What is asserted
 * here is what only this service can know — and the assertion with the most history behind
 * it is {@link #theEventPayloadIsAFreeFormObjectRatherThanAReflectedJavaClass()}, which
 * exists because M21.7 found the document confidently describing something no response has
 * ever contained.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false"
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
            "/v1/events",
            "/v1/events/{id}");

    @Override
    protected String serviceName() {
        return "audit-service";
    }

    @Override
    protected Set<String> publicPaths() {
        return EXPECTED_PATHS;
    }

    @Override
    protected List<String> tagNames() {
        return List.of("Events");
    }

    @Test
    void theTagsAreResourceNamesRatherThanTheJavaClassName() throws Exception {
        JsonNode paths = document().path("paths");

        // Left to springdoc this reads `event-controller` — an implementation detail that
        // would then name a section of the docs site (M25) and a group of SDK methods
        // (M22).
        assertThat(tagsOf(paths.path("/v1/events").path("get"))).containsExactly("Events");
        assertThat(tagsOf(paths.path("/v1/events/{id}").path("get"))).containsExactly("Events");
    }

    @Test
    void theResourceSchemaIsGeneratedFromTheDtoRatherThanLeftABareObject() throws Exception {
        JsonNode schemas = document().path("components").path("schemas");

        // The event object a merchant receives in a webhook body and reads back here is
        // the same shape (D140); a generator that finds it untyped produces an SDK of Maps.
        assertThat(names(schemas.path("EventResponse").path("properties")))
                .contains("id", "object", "type", "mode", "created", "data");
    }

    @Test
    void theEventPayloadIsAFreeFormObjectRatherThanAReflectedJavaClass() throws Exception {
        // M21.7, and the clearest contract defect this milestone found. `data` is a Jackson
        // JsonNode, and left to reflection springdoc described the *Java class*: it
        // published `isArray`, `isBigDecimal`, `getNodeType` and eighteen more bean getters
        // as the schema of the event payload, referenced through a generated `JsonNode`
        // component. That is a confident, valid-looking description of a shape no response
        // has ever had — the real body is the payment the event happened to.
        //
        // Asserted in both directions: the payload is an open object, and the bogus
        // component is gone rather than merely unreferenced.
        JsonNode data = document().path("components").path("schemas")
                .path("EventResponse").path("properties").path("data");

        assertThat(data.path("type").asText()).isEqualTo("object");
        assertThat(data.has("$ref")).isFalse();
        assertThat(data.path("description").asText()).isNotEmpty();
        assertThat(names(document().path("components").path("schemas")))
                .describedAs("the reflected JsonNode schema is no longer published")
                .doesNotContain("JsonNode");
    }

    @Test
    void theListPublishesItsFilterAndPaginationParameters() throws Exception {
        JsonNode parameters = document().path("paths").path("/v1/events").path("get").path("parameters");
        List<String> names = new ArrayList<>();
        parameters.forEach(parameter -> names.add(parameter.path("name").asText()));

        // The wire spelling (snake_case), not the Java argument names. `type` is the one
        // filter this list offers, and a spec that omitted it would leave SDK users
        // paging the whole trail to find one event kind.
        assertThat(names).contains("limit", "starting_after", "type", "created_after", "created_before");
        // Every one optional: a generator that saw otherwise would emit an SDK demanding a
        // cursor on the first call, when there is nothing to page from yet.
        parameters.forEach(parameter ->
                assertThat(parameter.path("required").asBoolean(false))
                        .describedAs("%s is optional", parameter.path("name").asText())
                        .isFalse());
    }

    @Test
    void theRetrievalDocumentsTheMalformedIdCaseSeparatelyFromTheMissingOne() throws Exception {
        // M21.7. `evt_zzzz` and a well-formed id nobody owns fail differently — 400 and 404
        // — and an SDK that treated both as "not found" would swallow a caller's own bug.
        JsonNode responses = document().path("paths").path("/v1/events/{id}").path("get")
                .path("responses");

        assertThat(names(responses)).contains("400", "404");
    }
}
