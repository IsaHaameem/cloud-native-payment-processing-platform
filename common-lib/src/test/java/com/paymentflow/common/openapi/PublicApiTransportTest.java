package com.paymentflow.common.openapi;

import com.paymentflow.common.correlation.CorrelationConstants;
import com.paymentflow.common.dto.http.PublicApiHeaders;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transport headers applied to the published document (M22.0).
 *
 * <p>What is worth testing here is not that headers appear — that is one line of the
 * implementation — but that the <em>right</em> ones appear on the right statuses. The
 * per-status rule encodes the gateway's filter order, and getting it wrong produces a
 * document that is valid, complete-looking, and lying about a header an SDK will wait on.
 */
class PublicApiTransportTest {

    private static final Set<String> RATE_LIMIT_HEADERS = Set.of(
            PublicApiHeaders.RATE_LIMIT_LIMIT,
            PublicApiHeaders.RATE_LIMIT_REMAINING,
            PublicApiHeaders.RATE_LIMIT_RESET);

    private static final Set<String> VERSION_HEADERS = Set.of(
            PublicApiHeaders.VERSION,
            PublicApiHeaders.DEPRECATION,
            PublicApiHeaders.SUNSET,
            PublicApiHeaders.LINK);

    /** A document shaped like a real fragment: one operation, the statuses M21.4 guarantees. */
    private static OpenAPI documentWithResponses(String... statuses) {
        ApiResponses responses = new ApiResponses();
        for (String status : statuses) {
            responses.addApiResponse(status, new ApiResponse().description("a response"));
        }
        Operation operation = new Operation().operationId("createPayment").responses(responses);
        return new OpenAPI().paths(new Paths().addPathItem("/v1/payments", new PathItem().post(operation)));
    }

    private static Operation only(OpenAPI document) {
        return document.getPaths().get("/v1/payments").getPost();
    }

    private static Set<String> headerNames(OpenAPI document, String status) {
        return only(document).getResponses().get(status).getHeaders().keySet();
    }

    // ── The definitions ─────────────────────────────────────────────────────────────────

    @Test
    void everyTransportHeaderIsDefinedOnceAsAComponentAndDescribed() {
        OpenAPI document = documentWithResponses("200");

        PublicApiTransport.apply(document);

        assertThat(document.getComponents().getHeaders().keySet())
                .describedAs("the transport contract, defined once each rather than inlined "
                        + "on every response")
                .containsExactlyInAnyOrder(
                        CorrelationConstants.CORRELATION_ID_HEADER,
                        CorrelationConstants.REQUEST_ID_HEADER,
                        PublicApiHeaders.VERSION,
                        PublicApiHeaders.RATE_LIMIT_LIMIT,
                        PublicApiHeaders.RATE_LIMIT_REMAINING,
                        PublicApiHeaders.RATE_LIMIT_RESET,
                        PublicApiHeaders.RETRY_AFTER,
                        PublicApiHeaders.DEPRECATION,
                        PublicApiHeaders.SUNSET,
                        PublicApiHeaders.LINK);

        assertThat(document.getComponents().getHeaders().values()).allSatisfy(header -> {
            assertThat(header.getDescription()).isNotBlank();
            assertThat(header.getSchema()).isNotNull();
            // Every one of these is conditional on something. A header marked required that
            // is legitimately absent reports the platform as broken rather than the document.
            assertThat(header.getRequired()).isFalse();
        });
    }

    @Test
    void responsesReferenceTheComponentsRatherThanInliningThem() {
        OpenAPI document = documentWithResponses("200");

        PublicApiTransport.apply(document);

        assertThat(only(document).getResponses().get("200").getHeaders().values())
                .allSatisfy(header -> assertThat(header.get$ref())
                        .describedAs("a $ref into components.headers, so a change to what a "
                                + "header means cannot land as one response's local detail")
                        .startsWith("#/components/headers/"));
    }

    // ── The per-status rule: the gateway's filter order, written down ────────────────────

    @Test
    void aSuccessResponseCarriesEverythingExceptRetryAfter() {
        OpenAPI document = documentWithResponses("201");

        PublicApiTransport.apply(document);

        assertThat(headerNames(document, "201"))
                .contains(CorrelationConstants.CORRELATION_ID_HEADER, CorrelationConstants.REQUEST_ID_HEADER)
                .containsAll(RATE_LIMIT_HEADERS)
                .containsAll(VERSION_HEADERS)
                .describedAs("Retry-After answers a question a 2xx did not ask")
                .doesNotContain(PublicApiHeaders.RETRY_AFTER);
    }

    @Test
    void aCredentialFailureCarriesNeitherQuotaNorVersionHeaders() {
        OpenAPI document = documentWithResponses("401", "403");

        PublicApiTransport.apply(document);

        for (String status : List.of("401", "403")) {
            assertThat(headerNames(document, status))
                    .describedAs("a %s is written by ApiKeyAuthenticationWebFilter at order "
                            + "+20 — the rate limiter (+30) and the version filter (+40) never "
                            + "run, so neither header is ever on it", status)
                    .containsExactly(CorrelationConstants.CORRELATION_ID_HEADER,
                            CorrelationConstants.REQUEST_ID_HEADER);
        }
    }

    @Test
    void aRateLimitRefusalCarriesTheQuotaHeadersAndRetryAfterButNoRevision() {
        OpenAPI document = documentWithResponses("429");

        PublicApiTransport.apply(document);

        assertThat(headerNames(document, "429"))
                .describedAs("refused at +30: the quota is known, the revision was never resolved")
                .contains(CorrelationConstants.CORRELATION_ID_HEADER, CorrelationConstants.REQUEST_ID_HEADER,
                        PublicApiHeaders.RETRY_AFTER)
                .containsAll(RATE_LIMIT_HEADERS)
                .doesNotContainAnyElementsOf(VERSION_HEADERS);
    }

    @Test
    void aServerErrorCarriesTheVersionHeadersBecauseItWasProducedDownstream() {
        OpenAPI document = documentWithResponses("500");

        PublicApiTransport.apply(document);

        assertThat(headerNames(document, "500"))
                .describedAs("a 500 comes from the service, past every gateway filter")
                .containsAll(VERSION_HEADERS)
                .containsAll(RATE_LIMIT_HEADERS)
                .doesNotContain(PublicApiHeaders.RETRY_AFTER);
    }

    @Test
    void everyResponseWhateverItsStatusCarriesTheCorrelationId() {
        // CorrelationIdWebFilter runs at HIGHEST_PRECEDENCE, before anything can refuse a
        // request. This is the one header with no exceptions, and it is what a caller quotes
        // in a support request when the response body was not one they could read.
        OpenAPI document = documentWithResponses("200", "400", "401", "403", "404", "409", "429", "500");

        PublicApiTransport.apply(document);

        only(document).getResponses().forEach((status, response) ->
                assertThat(response.getHeaders())
                        .describedAs("the %s response carries both trace identifiers", status)
                        .containsKeys(CorrelationConstants.CORRELATION_ID_HEADER,
                                CorrelationConstants.REQUEST_ID_HEADER));
    }

    // ── Request headers ─────────────────────────────────────────────────────────────────

    @Test
    void everyOperationAcceptsTheVersionAndCorrelationHeaders() {
        OpenAPI document = documentWithResponses("200");

        PublicApiTransport.apply(document);

        assertThat(only(document).getParameters())
                .extracting(Parameter::getName)
                .containsExactlyInAnyOrder(PublicApiHeaders.VERSION,
                        CorrelationConstants.CORRELATION_ID_HEADER);
        assertThat(only(document).getParameters()).allSatisfy(parameter -> {
            assertThat(parameter.getIn()).isEqualTo("header");
            assertThat(parameter.getRequired()).isFalse();
            // The document contract requires every parameter to explain itself; a customizer
            // that added an undescribed one would fail six services' contract tests instead
            // of this one, which is a worse place to find out.
            assertThat(parameter.getDescription()).isNotBlank();
        });
    }

    @Test
    void anOperationThatDeclaredTheHeaderItselfKeepsItsOwnDescription() {
        OpenAPI document = documentWithResponses("200");
        only(document).addParametersItem(new Parameter()
                .in("header")
                .name(PublicApiHeaders.VERSION)
                .description("This endpoint reads the revision for a reason of its own.")
                .schema(new StringSchema()));

        PublicApiTransport.apply(document);

        assertThat(only(document).getParameters())
                .filteredOn(parameter -> PublicApiHeaders.VERSION.equals(parameter.getName()))
                .singleElement()
                .extracting(Parameter::getDescription)
                .isEqualTo("This endpoint reads the revision for a reason of its own.");
    }

    @Test
    void aResponseThatDocumentedAHeaderItselfKeepsIt() {
        // The same rule PublicApiErrorResponses follows for responses: an operation that said
        // something specific knows something this class does not.
        OpenAPI document = documentWithResponses("200");
        only(document).getResponses().get("200")
                .addHeaderObject(PublicApiHeaders.VERSION,
                        new Header().description("Always the current revision here."));

        PublicApiTransport.apply(document);

        assertThat(only(document).getResponses().get("200").getHeaders()
                .get(PublicApiHeaders.VERSION).getDescription())
                .isEqualTo("Always the current revision here.");
    }

    // ── Idempotence, because springdoc may run a customizer more than once ───────────────

    @Test
    void applyingTwiceChangesNothing() {
        OpenAPI document = documentWithResponses("200", "429");

        PublicApiTransport.apply(document);
        int parameters = only(document).getParameters().size();
        Set<String> headers = headerNames(document, "429");

        PublicApiTransport.apply(document);

        assertThat(only(document).getParameters()).hasSize(parameters);
        assertThat(headerNames(document, "429")).isEqualTo(headers);
    }

    @Test
    void aDocumentWithNoPathsIsLeftAlone() {
        OpenAPI document = new OpenAPI();

        PublicApiTransport.apply(document);

        assertThat(document.getComponents()).isNull();
    }
}
