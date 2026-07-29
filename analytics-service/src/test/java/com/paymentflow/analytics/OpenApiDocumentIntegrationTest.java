package com.paymentflow.analytics;

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
 * The OpenAPI description of analytics-service's public surface (M21.2, rebased onto the
 * shared scaffold in M21.7).
 *
 * <p>The shared half is inherited from {@link PublicApiDocumentContract}. This service is
 * the one with genuinely nullable numbers in its responses — a success rate over zero
 * attempts, a p95 over zero requests — and M21.7 adds the assertion that the document says
 * so, because an SDK generated from a document that typed them non-null fails on precisely
 * the quiet-period responses.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "paymentflow.request-log.partition-initial-delay-ms=3600000",
        "paymentflow.request-log.rollup-initial-delay-ms=3600000",
        "paymentflow.request-log.retention-initial-delay-ms=3600000"
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
            "/v1/analytics/payments",
            "/v1/request_logs",
            "/v1/usage");

    @Override
    protected String serviceName() {
        return "analytics-service";
    }

    @Override
    protected Set<String> publicPaths() {
        return EXPECTED_PATHS;
    }

    @Override
    protected List<String> tagNames() {
        return List.of("Analytics", "Request logs", "Usage");
    }

    @Test
    void theTagsAreResourceNamesRatherThanTheJavaClassName() throws Exception {
        JsonNode paths = document().path("paths");

        // Left to springdoc these read `analytics-controller` and `request-log-controller`.
        // Exactly one tag each, not two: a class-level @Tag is added to every operation
        // rather than overridden (M21.1), and RequestLogController serves two resources.
        assertThat(tagsOf(paths.path("/v1/analytics/payments").path("get"))).containsExactly("Analytics");
        assertThat(tagsOf(paths.path("/v1/request_logs").path("get"))).containsExactly("Request logs");
        assertThat(tagsOf(paths.path("/v1/usage").path("get"))).containsExactly("Usage");
    }

    @Test
    void theResourceSchemasAreGeneratedFromTheDtosRatherThanLeftAsBareObjects() throws Exception {
        JsonNode schemas = document().path("components").path("schemas");

        assertThat(names(schemas.path("AnalyticsSummaryResponse").path("properties")))
                .contains("from", "to", "successRate", "buckets");
        assertThat(names(schemas.path("RequestLogResponse").path("properties")))
                .contains("id", "object", "method", "path", "statusCode", "requestId");
        assertThat(names(schemas.path("UsageSummaryResponse").path("properties")))
                .contains("from", "to", "totalRequests", "buckets");
    }

    @Test
    void theMeasurementsThatCanBeUnknownAreDocumentedAsNullable() throws Exception {
        // M21.7. `successRate` is null when nothing was attempted and `p95DurationMs` is
        // null for a day with no traffic — in both cases the answer is *unknown*, not zero,
        // which is why D143 made this platform publish the null explicitly rather than
        // omitting the field. A document that typed them non-null would describe a response
        // the service does not produce.
        JsonNode successRate = document().path("components").path("schemas")
                .path("AnalyticsSummaryResponse").path("properties").path("successRate");
        JsonNode p95 = document().path("components").path("schemas")
                .path("UsageBucketResponse").path("properties").path("p95DurationMs");

        assertThat(isNullable(successRate))
                .describedAs("successRate is documented as nullable").isTrue();
        assertThat(isNullable(p95))
                .describedAs("p95DurationMs is documented as nullable").isTrue();
    }

    @Test
    void theRequestLogListPublishesItsFilters() throws Exception {
        JsonNode parameters = document().path("paths").path("/v1/request_logs").path("get")
                .path("parameters");
        List<String> names = new ArrayList<>();
        parameters.forEach(parameter -> names.add(parameter.path("name").asText()));

        // The wire spelling, not the Java argument names. `status_code` and `method` are
        // how a developer narrows a log to the calls that went wrong.
        assertThat(names).contains("limit", "starting_after", "created_after", "created_before",
                "status_code", "method");
    }

    @Test
    void theUsageEndpointTakesDatesRatherThanInstants() throws Exception {
        // The aggregate is per UTC day, so accepting an instant would imply a precision the
        // stored data does not have. A generator reading `date-time` would offer callers a
        // precision the platform silently discards.
        JsonNode parameters = document().path("paths").path("/v1/usage").path("get")
                .path("parameters");

        assertThat(parameters.size()).isEqualTo(2);
        parameters.forEach(parameter ->
                assertThat(parameter.path("schema").path("format").asText())
                        .describedAs("%s is a date", parameter.path("name").asText())
                        .isEqualTo("date"));
    }

    /** OpenAPI 3.1 spells nullability as a type union; 3.0 used a {@code nullable} flag. */
    private static boolean isNullable(JsonNode schema) {
        JsonNode type = schema.path("type");
        if (type.isArray()) {
            for (JsonNode candidate : type) {
                if ("null".equals(candidate.asText())) {
                    return true;
                }
            }
        }
        return schema.path("nullable").asBoolean(false);
    }
}
