package com.paymentflow.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The merge that produces {@code docs/openapi.yaml} (M21.3).
 *
 * <p>These are ordinary unit tests over hand-written fragments rather than assertions about
 * the real six, deliberately. The real fragments agree today — that is what D149 and
 * {@code PublicApiDocumentTest} are for — so a test that merged them would exercise only
 * the happy path and would keep passing right up until the day it mattered. What needs
 * proving is the behaviour on fragments that <em>disagree</em>, and the only way to have
 * those is to write them.
 */
class OpenApiMergerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final OpenApiMerger merger = new OpenApiMerger();

    @Test
    void twoServicesPathsBecomeOneDocument() {
        ObjectNode merged = merger.merge(List.of(
                fragment("payment-service", """
                        {"paths": {"/v1/payments": {"get": {"tags": ["Payments"]}}}}"""),
                fragment("audit-service", """
                        {"paths": {"/v1/events": {"get": {"tags": ["Events"]}}}}""")));

        assertThat(names(merged.path("paths"))).containsExactly("/v1/events", "/v1/payments");
    }

    @Test
    void pathsAreSortedSoTheBaselineDiffIsStable() {
        // The merged file is committed and diffed by M21.6. If path order followed whichever
        // service Gradle happened to finish first, every rebuild would look like a change.
        ObjectNode merged = merger.merge(List.of(
                fragment("z-service", """
                        {"paths": {"/v1/zebra": {}, "/v1/apple": {}}}"""),
                fragment("a-service", """
                        {"paths": {"/v1/mango": {}}}""")));

        assertThat(names(merged.path("paths")))
                .containsExactly("/v1/apple", "/v1/mango", "/v1/zebra");
    }

    @Test
    void aComponentDefinedIdenticallyByTwoServicesIsKeptOnce() {
        // The case the merge exists for. `ApiError` will be defined by all six once M21.4
        // documents error responses, exactly as `SecretKey` already is — identical, because
        // one shared class generates it, and therefore correct to collapse.
        ObjectNode merged = merger.merge(List.of(
                fragment("payment-service", """
                        {"components": {"schemas": {"ApiError": {"type": "object"}}}}"""),
                fragment("audit-service", """
                        {"components": {"schemas": {"ApiError": {"type": "object"}}}}""")));

        JsonNode schemas = merged.path("components").path("schemas");
        assertThat(names(schemas)).containsExactly("ApiError");
        assertThat(schemas.path("ApiError").path("type").asText()).isEqualTo("object");
    }

    @Test
    void aComponentDefinedDifferentlyByTwoServicesIsRefused() {
        // The failure this exists to catch: the merged document can hold only one
        // #/components/schemas/ApiError, so silently keeping either one would leave every
        // $ref in the other service's half pointing at a type that is not what it returns.
        assertThatThrownBy(() -> merger.merge(List.of(
                fragment("payment-service", """
                        {"components": {"schemas": {"ApiError": {"type": "object"}}}}"""),
                fragment("audit-service", """
                        {"components": {"schemas": {"ApiError": {"type": "string"}}}}"""))))
                .isInstanceOf(OpenApiMergeException.class)
                .hasMessageContaining("components.schemas.ApiError")
                .hasMessageContaining("payment-service")
                .hasMessageContaining("audit-service");
    }

    @Test
    void twoServicesPublishingTheSamePathIsRefused() {
        assertThatThrownBy(() -> merger.merge(List.of(
                fragment("payment-service", """
                        {"paths": {"/v1/payments": {}}}"""),
                fragment("audit-service", """
                        {"paths": {"/v1/payments": {}}}"""))))
                .isInstanceOf(OpenApiMergeException.class)
                .hasMessageContaining("/v1/payments")
                .hasMessageContaining("belongs to exactly one service");
    }

    @Test
    void fragmentsThatDisagreeOnTheContractVersionAreRefused() {
        // The exact drift D149 moved the info block into common-lib to prevent, asserted
        // here against the artefacts rather than the source: if a service ever grows a
        // second OpenAPI bean or a stray @OpenAPIDefinition, common-lib's test still passes
        // and this one does not.
        assertThatThrownBy(() -> merger.merge(List.of(
                fragment("payment-service", """
                        {"info": {"title": "PaymentFlow API", "version": "2026-07-27"}}"""),
                fragment("audit-service", """
                        {"info": {"title": "PaymentFlow API", "version": "2026-08-01"}}"""))))
                .isInstanceOf(OpenApiMergeException.class)
                .hasMessageContaining("`info` differs");
    }

    @Test
    void fragmentsThatDisagreeOnTheOpenApiVersionAreRefused() {
        // A fragment generated at 3.0 among 3.1 siblings changes the schema dialect, which
        // produces a document that validates and generates subtly wrong SDK types.
        assertThatThrownBy(() -> merger.merge(List.of(
                fragment("payment-service", """
                        {"openapi": "3.1.0"}"""),
                fragment("audit-service", """
                        {"openapi": "3.0.1"}"""))))
                .isInstanceOf(OpenApiMergeException.class)
                .hasMessageContaining("`openapi` differs");
    }

    @Test
    void everyDisagreementIsReportedRatherThanOnlyTheFirst() {
        // A renamed shared type breaks several things at once; fixing them one build at a
        // time is how a merge gate becomes something people work around.
        assertThatThrownBy(() -> merger.merge(List.of(
                fragment("payment-service", """
                        {"openapi": "3.1.0",
                         "info": {"version": "2026-07-27"},
                         "components": {"schemas": {"ApiError": {"type": "object"}}}}"""),
                fragment("audit-service", """
                        {"openapi": "3.0.1",
                         "info": {"version": "2026-08-01"},
                         "components": {"schemas": {"ApiError": {"type": "string"}}}}"""))))
                .isInstanceOf(OpenApiMergeException.class)
                .satisfies(e -> assertThat(((OpenApiMergeException) e).conflicts()).hasSize(3));
    }

    @Test
    void tagsAreUnionedInDeclarationOrderAndDeduplicated() {
        // Declaration order rather than alphabetical: this is the documentation site's
        // navigation (M25), and "Payments" belongs above "Balance" for reasons no sort can
        // know. The merge preserves the order the caller passes, which the build file states.
        ObjectNode merged = merger.merge(List.of(
                fragment("payment-service", """
                        {"tags": [{"name": "Payments", "description": "p"},
                                  {"name": "Refunds", "description": "r"}]}"""),
                fragment("transaction-service", """
                        {"tags": [{"name": "Balance", "description": "b"}]}""")));

        assertThat(values(merged.path("tags")).stream().map(t -> t.path("name").asText()).toList())
                .containsExactly("Payments", "Refunds", "Balance");
    }

    @Test
    void theSameTagDescribedTwoWaysIsRefused() {
        // One tag renders as one section on the docs site, so one of the two descriptions
        // would simply be discarded — silently, and differently depending on merge order.
        assertThatThrownBy(() -> merger.merge(List.of(
                fragment("payment-service", """
                        {"tags": [{"name": "Payments", "description": "one"}]}"""),
                fragment("sandbox-service", """
                        {"tags": [{"name": "Payments", "description": "another"}]}"""))))
                .isInstanceOf(OpenApiMergeException.class)
                .hasMessageContaining("tag `Payments`");
    }

    @Test
    void componentSectionsOtherThanSchemasAreMergedToo() {
        // `securitySchemes` today; `parameters` and `responses` the moment M21.4 factors the
        // shared error responses out. Merging only `schemas` would drop them without a word.
        ObjectNode merged = merger.merge(List.of(
                fragment("payment-service", """
                        {"components": {"securitySchemes": {"SecretKey": {"type": "http"}}}}"""),
                fragment("audit-service", """
                        {"components": {"parameters": {"limit": {"in": "query"}}}}""")));

        assertThat(names(merged.path("components")))
                .containsExactly("parameters", "securitySchemes");
    }

    @Test
    void theSharedContractIsCarriedOntoTheMergedDocument() {
        ObjectNode merged = merger.merge(List.of(
                fragment("payment-service", """
                        {"openapi": "3.1.0",
                         "info": {"title": "PaymentFlow API", "version": "2026-07-27"},
                         "servers": [{"url": "https://api.paymentflow.dev"}],
                         "security": [{"SecretKey": []}],
                         "paths": {"/v1/payments": {}}}"""),
                fragment("audit-service", """
                        {"openapi": "3.1.0",
                         "info": {"title": "PaymentFlow API", "version": "2026-07-27"},
                         "servers": [{"url": "https://api.paymentflow.dev"}],
                         "security": [{"SecretKey": []}],
                         "paths": {"/v1/events": {}}}""")));

        assertThat(merged.path("openapi").asText()).isEqualTo("3.1.0");
        assertThat(merged.path("info").path("version").asText()).isEqualTo("2026-07-27");
        assertThat(merged.path("servers").get(0).path("url").asText()).isEqualTo("https://api.paymentflow.dev");
        assertThat(names(merged.path("security").get(0))).containsExactly("SecretKey");
    }

    @Test
    void mergingNothingIsAnErrorRatherThanAnEmptyDocument() {
        // The failure mode this guards is a build that "succeeds" because the fragment task
        // silently produced no files — publishing an empty contract is worse than not
        // publishing one.
        assertThatThrownBy(() -> merger.merge(List.of()))
                .isInstanceOf(OpenApiMergeException.class)
                .hasMessageContaining("no fragments");
    }

    @Test
    void aSingleFragmentMergesToItself() {
        assertThatCode(() -> merger.merge(List.of(
                fragment("payment-service", """
                        {"openapi": "3.1.0", "paths": {"/v1/payments": {}}}"""))))
                .doesNotThrowAnyException();
    }

    private static OpenApiMerger.Fragment fragment(String source, String json) {
        try {
            return new OpenApiMerger.Fragment(source, JSON.readTree(json));
        } catch (Exception e) {
            throw new IllegalArgumentException("bad test fixture for " + source, e);
        }
    }

    /**
     * The property names of an object node, in document order.
     *
     * <p>Spelled out here because this module is on Jackson 2 while the services are on
     * Jackson 3 (see the build file): {@code propertyNames()} and {@code valueStream()} are
     * the 3.x spellings, and the 2.x equivalents are {@code properties()} and iteration.
     */
    private static List<String> names(JsonNode object) {
        return object.properties().stream().map(Map.Entry::getKey).toList();
    }

    /** The elements of an array node, as a list. */
    private static List<JsonNode> values(JsonNode array) {
        List<JsonNode> elements = new ArrayList<>();
        array.forEach(elements::add);
        return elements;
    }
}
