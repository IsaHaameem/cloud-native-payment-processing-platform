package com.paymentflow.sandbox;

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
 * The OpenAPI description of sandbox-service's public surface (M21.2, rebased onto the
 * shared scaffold in M21.7).
 *
 * <p>The shared half is inherited from {@link PublicApiDocumentContract} — including the
 * universal-error rule, which knows about the exception this service owns: an operation that
 * declares {@code security: []} must <em>not</em> document 401 or 403.
 *
 * <p>That exception is this fragment's whole peculiarity. {@code GET /v1/test/cards} is the
 * platform's one genuinely unauthenticated public endpoint (§8.1), and a document that
 * described it as needing a key would send SDK users looking for a credential to read the
 * catalogue that tells them which credentials to test with.
 */
@SpringBootTest
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
            "/v1/test/cards",
            "/v1/test/simulations",
            "/v1/test/simulations/active",
            "/v1/test/decisions",
            "/v1/test/decisions/payments/{paymentId}");

    @Override
    protected String serviceName() {
        return "sandbox-service";
    }

    @Override
    protected Set<String> publicPaths() {
        return EXPECTED_PATHS;
    }

    @Override
    protected List<String> tagNames() {
        return List.of("Test cards", "Simulations", "Decisions");
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
            for (String verb : names(paths.path(path))) {
                assertThat(paths.path(path).path(verb).has("security"))
                        .describedAs("%s %s inherits the document-level SecretKey requirement", verb, path)
                        .isFalse();
            }
        }
    }

    @Test
    void theMutatingSimulationControlsAreDocumentedAlongsideTheReads() throws Exception {
        JsonNode paths = document().path("paths");

        // A path-prefix filter says nothing about verbs, and this tier is how a developer
        // forces a failure path on demand — an SDK missing POST or DELETE here would make
        // the sandbox read-only.
        assertThat(names(paths.path("/v1/test/simulations"))).containsExactly("post");
        assertThat(names(paths.path("/v1/test/simulations/active")))
                .containsExactlyInAnyOrder("get", "delete");
        assertThat(names(paths.path("/v1/test/cards"))).containsExactly("get");
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
    void theResourceSchemasAreGeneratedFromTheDtosRatherThanLeftAsBareObjects() throws Exception {
        JsonNode schemas = document().path("components").path("schemas");

        assertThat(names(schemas.path("TestCardResponse").path("properties")))
                .contains("token", "brand", "outcome", "captureBehaviour");
        assertThat(names(schemas.path("SimulationOverrideResponse").path("properties")))
                .contains("id", "scenario", "remainingCount", "expiresAt");
        assertThat(names(schemas.path("CreateSimulationOverrideRequest").path("properties")))
                .contains("scenario", "declineCode", "errorCode", "latencyMs");
    }

    @Test
    void theOffsetPagedDecisionLogPublishesPageAndSizeRatherThanTheJavaPageableArgument()
            throws Exception {
        JsonNode parameters = document().path("paths").path("/v1/test/decisions").path("get")
                .path("parameters");
        List<String> names = new ArrayList<>();
        parameters.forEach(parameter -> names.add(parameter.path("name").asText()));

        // Same hazard as notification-service's delivery list: `Pageable` is a Spring
        // binding type, and published verbatim it becomes a `pageable` object argument no
        // caller can send. @ParameterObject expands it into the real query parameters.
        assertThat(names).contains("page", "size");
        assertThat(names).doesNotContain("pageable");
    }

    @Test
    void theUnauthenticatedEndpointIsNotDocumentedAsReturning401Or403() throws Exception {
        // The counterpart to the security assertion above (M21.4). `GET /v1/test/cards`
        // needs no credential, so it cannot fail to authenticate — documenting a 401 on it
        // would contradict the running system and make an SDK generate credential handling
        // for a call that takes none. Every other operation here still declares both.
        Set<String> cardResponses = names(document().path("paths").path("/v1/test/cards")
                .path("get").path("responses"));

        assertThat(cardResponses).doesNotContain("401", "403");
        // 429 and 500 remain: unauthenticated traffic is still rate limited by IP (D24),
        // and anything can fail.
        assertThat(cardResponses).contains("429", "500");

        assertThat(names(document().path("paths").path("/v1/test/simulations").path("post")
                .path("responses"))).contains("401", "403");
    }

    @Test
    void theCatalogueExplainsWhatEachBehaviourFieldSelects() throws Exception {
        // M21.7. This resource is the first thing an integrator reads, before they have made
        // a single successful call, and `captureBehaviour` versus `outcome` is the
        // distinction that decides whether they pick the right token — a card can authorize
        // cleanly and still fail to capture, which is exactly the case people miss.
        JsonNode properties = document().path("components").path("schemas")
                .path("TestCardResponse").path("properties");

        assertThat(properties.path("outcome").path("description").asText()).isNotEmpty();
        assertThat(properties.path("captureBehaviour").path("description").asText())
                .contains("capture");
        assertThat(properties.path("token").path("description").asText())
                .contains("paymentMethodToken");
    }
}
