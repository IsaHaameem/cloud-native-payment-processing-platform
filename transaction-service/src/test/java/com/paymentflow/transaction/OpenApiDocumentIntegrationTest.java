package com.paymentflow.transaction;

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
 * The OpenAPI description of transaction-service's public surface (M21.2, rebased onto the
 * shared scaffold in M21.7).
 *
 * <p>The shared half — 3.1, the excluded internal tiers, the shared contract, the prose
 * rules — is inherited from {@link PublicApiDocumentContract}. What is asserted here is what
 * only this service can know: that the document describes <em>its</em> endpoints, all of
 * them, and nothing else.
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@Testcontainers
class OpenApiDocumentIntegrationTest extends PublicApiDocumentContract {

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

    @Override
    protected String serviceName() {
        return "transaction-service";
    }

    @Override
    protected Set<String> publicPaths() {
        return EXPECTED_PATHS;
    }

    @Override
    protected List<String> tagNames() {
        return List.of("Balance", "Balance transactions");
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
    void theResourceSchemasAreGeneratedFromTheDtosRatherThanLeftAsBareObjects() throws Exception {
        JsonNode schemas = document().path("components").path("schemas");

        // The document is only worth publishing if the bodies are described; a generator
        // that finds them untyped produces an SDK of Maps.
        assertThat(names(schemas.path("BalanceResponse").path("properties"))).isNotEmpty();
        assertThat(names(schemas.path("BalanceTransactionResponse").path("properties")))
                .contains("id", "amountMinor", "currency");
    }

    @Test
    void theCursorPagedListPublishesItsPaginationParameters() throws Exception {
        JsonNode parameters = document().path("paths").path("/v1/balance_transactions")
                .path("get").path("parameters");
        List<String> names = new ArrayList<>();
        parameters.forEach(parameter -> names.add(parameter.path("name").asText()));

        // The wire spelling (snake_case), not the Java argument names — these are what a
        // caller actually sends, and M19's cursor contract is only usable if they appear.
        assertThat(names).contains("limit", "starting_after", "created_after", "created_before");
        // Every one of them is optional; a generator that saw otherwise would emit an SDK
        // demanding a cursor on the first call, when there is nothing to page from yet.
        parameters.forEach(parameter ->
                assertThat(parameter.path("required").asBoolean(false))
                        .describedAs("%s is optional", parameter.path("name").asText())
                        .isFalse());
    }

    @Test
    void theBalanceEnvelopeDescribesTheTwoFiguresSeparately() throws Exception {
        // M21.7. `pending` and `available` are the whole point of this resource and the
        // single most misread pair on it — pending money is not yours yet. A document that
        // named them without saying so would be correctly typed and actively misleading.
        JsonNode balance = document().path("components").path("schemas")
                .path("CurrencyBalance").path("properties");

        assertThat(balance.path("pendingMinor").path("description").asText())
                .contains("authorized").contains("not yet captured");
        assertThat(balance.path("availableMinor").path("description").asText())
                .contains("captured");
    }
}
