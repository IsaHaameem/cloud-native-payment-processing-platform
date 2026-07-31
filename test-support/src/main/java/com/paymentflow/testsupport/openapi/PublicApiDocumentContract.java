package com.paymentflow.testsupport.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.paymentflow.common.correlation.CorrelationConstants;
import com.paymentflow.common.dto.error.ErrorType;
import com.paymentflow.common.dto.http.PublicApiHeaders;
import com.paymentflow.common.openapi.PublicApiDocument;
import com.paymentflow.openapi.OpenApiFragments;
import com.paymentflow.openapi.OpenApiYaml;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Everything that is true of <em>every</em> service's OpenAPI fragment (M21.7).
 *
 * <p><b>Why this is shared.</b> Six services each had their own copy of these assertions —
 * the same cached {@code /v3/api-docs} fetch, the same {@code tagsOf}/{@code usedTags}
 * helpers, and five checks that were identical in intent and nearly identical in text.
 * Roughly seventy lines each, six times, against §5.0 standing rule 4. The cost was never
 * the line count; it was that the six copies could drift, and that a seventh service would
 * inherit whichever copy happened to be pasted. M21.7 adds six more assertions to that same
 * scaffold, so copying it once more was not an option.
 *
 * <p><b>What stays in the subclass.</b> Anything only that service can know: which paths it
 * publishes, which tags it owns, that {@code GET /v1/test/cards} is unauthenticated, that
 * the decision log pages by {@code page}/{@code size} rather than a Spring {@code Pageable}.
 * The rule is the same one D149 applied to the document itself — shared where the *API* is
 * being described, per-service where a *service* is.
 *
 * <p>A subclass supplies its containers, its {@code @SpringBootTest} configuration, and the
 * three facts below. It inherits eighteen tests.
 */
@AutoConfigureMockMvc
public abstract class PublicApiDocumentContract {

    /**
     * Descriptions springdoc generates when nobody wrote one. Treated as absent, because
     * that is what they are: a document where every 200 says "OK" has not been written, it
     * has been generated, and the difference is invisible to anything but a reader.
     */
    private static final Set<String> GENERATED_DESCRIPTIONS =
            Set.of("OK", "Created", "Accepted", "No Content", "default response", "");

    @Autowired
    protected MockMvc mockMvc;

    /** Cached per test instance; the document is one artefact, not fourteen fetches. */
    private JsonNode document;
    private String documentJson;

    // ── What only the service knows ─────────────────────────────────────────────────────

    /** The Gradle module name — the fragment's file name, and its label in merge conflicts. */
    protected abstract String serviceName();

    /**
     * Every path this service publishes on the public tier. Spelled out by the subclass
     * rather than reflected off its controllers: a test that derived its expectation from
     * the same source as the thing it checks would pass however the mappings changed.
     */
    protected abstract Set<String> publicPaths();

    /** The resource tags this service owns, in the order they should appear on the docs site. */
    protected abstract List<String> tagNames();

    // ── The document ────────────────────────────────────────────────────────────────────

    protected final JsonNode document() throws Exception {
        if (document == null) {
            // No credential of any kind on this request (D148). If the endpoint ever stops
            // being permitted, this fails here rather than in CI's contract job with a 401
            // body that does not parse as OpenAPI.
            documentJson = mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            document = OpenApiYaml.read(documentJson);
        }
        return document;
    }

    // ── The shared contract ─────────────────────────────────────────────────────────────

    @Test
    void theDocumentIsOpenApi31() throws Exception {
        // springdoc's default is "3.0.1" whatever its own version, and this string is the
        // only thing a generator reads to decide which schema dialect it is parsing.
        assertThat(document().path("openapi").asText()).startsWith("3.1");
    }

    @Test
    void everyDocumentedPathIsPublicAndEveryPublicPathIsDocumented() throws Exception {
        assertThat(names(document().path("paths")))
                .describedAs("the published spec describes exactly this service's public /v1 tier")
                .containsExactlyInAnyOrderElementsOf(publicPaths());
    }

    @Test
    void theInternalTiersAreAbsentRatherThanMerelyUnlisted() throws Exception {
        Set<String> paths = names(document().path("paths"));

        // One line of YAML keeps these out (`springdoc.paths-to-match`), and a line of YAML
        // nobody re-reads is how a dashboard route ends up in an SDK two milestones later.
        assertThat(paths).noneMatch(path -> path.startsWith("/api/v1"));
        assertThat(paths).noneMatch(path -> path.startsWith("/internal/"));
        assertThat(paths).noneMatch(path -> path.startsWith("/actuator"));
        // Spring's own error handler, not an endpoint anyone integrates against.
        assertThat(paths).doesNotContain("/error");
    }

    @Test
    void theDocumentCarriesTheSharedContractRatherThanItsOwn() throws Exception {
        JsonNode info = document().path("info");

        // The integration half of what PublicApiDocumentTest proves in isolation: the
        // fragment this service actually *serves* carries the shared values, so a stray
        // local @OpenAPIDefinition or a second OpenAPI bean is caught here.
        assertThat(info.path("title").asText()).isEqualTo(PublicApiDocument.TITLE);
        assertThat(info.path("version").asText()).isEqualTo(PublicApiDocument.API_VERSION);
        assertThat(info.path("description").asText()).isNotEmpty();

        JsonNode servers = document().path("servers");
        // springdoc infers the server from the incoming request unless told otherwise,
        // which would publish the test's own random port as the host to call.
        assertThat(servers.size()).isEqualTo(1);
        assertThat(servers.get(0).path("url").asText()).isEqualTo(PublicApiDocument.PUBLIC_SERVER_URL);

        JsonNode scheme = document().path("components").path("securitySchemes")
                .path(PublicApiDocument.SECRET_KEY_SCHEME);
        assertThat(scheme.path("type").asText()).isEqualTo("http");
        assertThat(scheme.path("scheme").asText()).isEqualTo("bearer");
        // Deliberately not "JWT": the value is an opaque sk_ key, and saying otherwise
        // invites an SDK author to decode it.
        assertThat(scheme.path("bearerFormat").asText()).isEqualTo("sk");
        assertThat(names(document().path("security").get(0)))
                .containsExactly(PublicApiDocument.SECRET_KEY_SCHEME);
    }

    @Test
    void everySuccessResponseIsTypedAsJsonRatherThanAnythingAtAll() throws Exception {
        forEachOperation((path, verb, operation) ->
                operation.path("responses").properties().forEach(response -> {
                    if (!response.getKey().startsWith("2")) {
                        return;
                    }
                    JsonNode content = response.getValue().path("content");
                    if (content.isMissingNode()) {
                        // 204 and friends legitimately have no body.
                        return;
                    }
                    // springdoc's default is `*/*` when a handler declares no `produces`,
                    // and none here do. An SDK author reading that has to guess the Accept
                    // header.
                    assertThat(names(content))
                            .describedAs("%s %s (%s) declares its media type", verb, path, response.getKey())
                            .containsExactly("application/json");
                }));
    }

    @Test
    void everyTagUsedByAnOperationIsDeclaredAndDescribed() throws Exception {
        JsonNode declared = document().path("tags");
        List<String> declaredNames = new ArrayList<>();
        declared.forEach(tag -> declaredNames.add(tag.path("name").asText()));

        assertThat(declaredNames).containsExactlyElementsOf(tagNames());
        declared.forEach(tag -> assertThat(tag.path("description").asText())
                .describedAs("the tag `%s` is described", tag.path("name").asText())
                .isNotEmpty());

        // The cross-check that lets the two spellings live apart: an operation tagged with
        // a name the document never declares renders as an unnamed, undescribed section on
        // the docs site — valid OpenAPI, useless output.
        Set<String> used = new LinkedHashSet<>();
        forEachOperation((path, verb, operation) ->
                operation.path("tags").forEach(tag -> used.add(tag.asText())));
        assertThat(used).isNotEmpty();
        assertThat(declaredNames).containsAll(used);
    }

    @Test
    void everyOperationDocumentsTheStandardErrorResponses() throws Exception {
        forEachOperation((path, verb, operation) -> {
            Set<String> responses = names(operation.path("responses"));
            String where = "%s %s".formatted(verb, path);

            // Applied from one customizer in common-lib rather than annotated 124 times
            // (M21.4), so what is worth asserting is the outcome. An SDK generated from a
            // document where one operation forgot its 401 has no error type for that call,
            // and nothing about the document would look wrong.
            assertThat(responses).describedAs("%s documents the universal errors", where)
                    .contains("429", "500");

            if (isUnauthenticated(operation)) {
                // The platform's one genuinely credential-free endpoint (§8.1). It cannot
                // fail to authenticate, and documenting a 401 on it would make a generator
                // emit credential handling for a call that takes none.
                assertThat(responses).describedAs("%s needs no credential, so it cannot 401", where)
                        .doesNotContain("401", "403");
            } else {
                assertThat(responses).describedAs("%s documents the credential errors", where)
                        .contains("401", "403");
            }
        });
    }

    // ── The prose (M21.7, D154) ─────────────────────────────────────────────────────────

    @Test
    void everyOperationCarriesASummaryAndADescription() throws Exception {
        forEachOperation((path, verb, operation) -> {
            String where = "%s %s".formatted(verb, path);
            // The summary is the line a docs site puts in its navigation and an SDK puts in
            // its method's doc comment; the description is what a reader consults when the
            // summary was not enough. A document with neither renders and validates
            // perfectly, which is exactly why it went unnoticed until something read it.
            assertThat(operation.path("summary").asText())
                    .describedAs("%s has a summary", where)
                    .isNotEmpty();
            assertThat(operation.path("description").asText())
                    .describedAs("%s has a description", where)
                    .isNotEmpty();
        });
    }

    @Test
    void everySuccessResponseSaysMoreThanSpringdocsDefault() throws Exception {
        forEachOperation((path, verb, operation) ->
                operation.path("responses").properties().forEach(response -> {
                    if (!response.getKey().startsWith("2")) {
                        return;
                    }
                    // "OK" is not a description, it is the absence of one wearing a word.
                    // Asserted against the generated defaults rather than merely against
                    // emptiness, because springdoc always fills something in.
                    assertThat(response.getValue().path("description").asText())
                            .describedAs("%s %s documents what its %s response means",
                                    verb, path, response.getKey())
                            .isNotIn(GENERATED_DESCRIPTIONS);
                }));
    }

    @Test
    void everyParameterExplainsWhatItDoes() throws Exception {
        forEachOperation((path, verb, operation) ->
                operation.path("parameters").forEach(parameter ->
                        assertThat(parameter.path("description").asText())
                                .describedAs("%s %s: the `%s` parameter is described",
                                        verb, path, parameter.path("name").asText())
                                .isNotEmpty()));
    }

    @Test
    void everyPublishedFieldIsDescribed() throws Exception {
        JsonNode schemas = document().path("components").path("schemas");
        List<String> undescribed = new ArrayList<>();

        schemas.properties().forEach(schema ->
                schema.getValue().path("properties").properties().forEach(property -> {
                    if (property.getValue().path("description").asText("").isEmpty()) {
                        undescribed.add(schema.getKey() + "." + property.getKey());
                    }
                }));

        // Reported as one list rather than one failure at a time: this is the assertion that
        // drove M21.7's documentation work, and discovering the remaining fields one build
        // per field would have made it unbearable.
        assertThat(undescribed)
                .describedAs("every field in the published schemas explains itself")
                .isEmpty();
    }

    @Test
    void everyOperationIdIsUniqueAndDeliberate() throws Exception {
        List<String> ids = new ArrayList<>();
        forEachOperation((path, verb, operation) -> {
            String id = operation.path("operationId").asText();
            assertThat(id).describedAs("%s %s declares an operation id", verb, path).isNotEmpty();
            ids.add(id);
        });

        // An operation id names the method in every generated SDK (M22). Left to springdoc
        // it is the Java handler's method name — which makes the SDK's shape a consequence
        // of how the controller was refactored, and collides the moment two controllers
        // have a method called `list`.
        assertThat(new TreeSet<>(ids))
                .describedAs("operation ids are unique within the service")
                .hasSize(ids.size());
    }

    // ── The transport contract (M22.0) ──────────────────────────────────────────────────

    @Test
    void everyOperationAcceptsTheSharedTransportRequestHeaders() throws Exception {
        forEachOperation((path, verb, operation) -> {
            Set<String> headers = new LinkedHashSet<>();
            operation.path("parameters").forEach(parameter -> {
                if ("header".equals(parameter.path("in").asText())) {
                    headers.add(parameter.path("name").asText());
                }
            });
            // Both are accepted on every call and neither is operation-specific, so an
            // operation missing one means an SDK that pins a revision per request would be
            // sending a header the contract does not describe.
            assertThat(headers).describedAs("%s %s accepts the transport request headers", verb, path)
                    .contains(PublicApiHeaders.VERSION, CorrelationConstants.CORRELATION_ID_HEADER);
        });
    }

    @Test
    void everyResponseDocumentsExactlyTheTransportHeadersItsStatusCanCarry() throws Exception {
        forEachOperation((path, verb, operation) ->
                operation.path("responses").properties().forEach(response -> {
                    String status = response.getKey();
                    Set<String> headers = names(response.getValue().path("headers"));
                    String where = "%s %s (%s)".formatted(verb, path, status);

                    // Positive direction. These two have no exceptions — their filter runs at
                    // HIGHEST_PRECEDENCE, before anything can refuse a request. The request id
                    // in particular must be on *successes* too: it keys the caller's own
                    // request-log rows, and an SDK that could only report it on failure would
                    // leave a payment that succeeded strangely impossible to trace (D168).
                    assertThat(headers).describedAs("%s carries both trace identifiers", where)
                            .contains(CorrelationConstants.CORRELATION_ID_HEADER,
                                    CorrelationConstants.REQUEST_ID_HEADER);

                    // Negative direction, and the half worth having. The gateway's filter
                    // order decides what a refusal can carry: a 401/403 is written at +20 and
                    // a 429 at +30, so neither has been through the version filter at +40.
                    // Documenting a revision on them would send an SDK looking for a header
                    // the platform never writes.
                    if (Set.of("401", "403", "429").contains(status)) {
                        assertThat(headers)
                                .describedAs("%s is refused at the edge, before the revision "
                                        + "is resolved", where)
                                .doesNotContain(PublicApiHeaders.VERSION, PublicApiHeaders.DEPRECATION,
                                        PublicApiHeaders.SUNSET, PublicApiHeaders.LINK);
                    } else {
                        assertThat(headers).describedAs("%s reports the revision that answered", where)
                                .contains(PublicApiHeaders.VERSION);
                    }
                    if (Set.of("401", "403").contains(status)) {
                        assertThat(headers)
                                .describedAs("%s is refused before any quota is measured", where)
                                .doesNotContain(PublicApiHeaders.RATE_LIMIT_LIMIT,
                                        PublicApiHeaders.RATE_LIMIT_REMAINING,
                                        PublicApiHeaders.RATE_LIMIT_RESET);
                    } else {
                        assertThat(headers).describedAs("%s reports the quota window", where)
                                .contains(PublicApiHeaders.RATE_LIMIT_LIMIT,
                                        PublicApiHeaders.RATE_LIMIT_REMAINING,
                                        PublicApiHeaders.RATE_LIMIT_RESET);
                    }

                    // Retry-After answers "when may I try again", which only a refusal asks.
                    assertThat(headers.contains(PublicApiHeaders.RETRY_AFTER))
                            .describedAs("%s documents Retry-After only if it is a 429", where)
                            .isEqualTo("429".equals(status));
                }));
    }

    @Test
    void everyTransportHeaderIsDefinedOnceAndEveryDefinitionIsUsed() throws Exception {
        JsonNode defined = document().path("components").path("headers");
        assertThat(names(defined))
                .describedAs("the transport headers are declared as components rather than "
                        + "inlined on each of the document's responses")
                .isNotEmpty();

        defined.properties().forEach(header -> {
            assertThat(header.getValue().path("description").asText())
                    .describedAs("the `%s` header explains itself", header.getKey())
                    .isNotEmpty();
            // Read off the *served* document, not the builder. swagger's 3.1 serializer reads
            // the `types` set, so a header schema built with `setType` alone renders with its
            // `format` and no type at all — valid-looking, and it tells a generator nothing
            // about what kind of value the header carries. §18 warning 4 is the standing
            // instruction to check the output rather than the annotation; this is that check.
            assertThat(header.getValue().path("schema").path("type").asText())
                    .describedAs("the `%s` header declares the type of its value", header.getKey())
                    .isNotEmpty();
        });

        Set<String> referenced = new LinkedHashSet<>();
        forEachOperation((path, verb, operation) ->
                operation.path("responses").properties().forEach(response ->
                        response.getValue().path("headers").properties().forEach(header -> {
                            // Every one must be a reference; an inlined definition would be a
                            // second copy that M21.3's merge cannot deduplicate and that
                            // OpenApiDiff cannot judge as one shared change.
                            assertThat(header.getValue().path("$ref").asText())
                                    .describedAs("%s %s (%s) references the `%s` component",
                                            verb, path, response.getKey(), header.getKey())
                                    .isEqualTo("#/components/headers/" + header.getKey());
                            referenced.add(header.getKey());
                        })));

        // The other direction: a header defined and referenced by nothing is a promise in the
        // published contract that no response in this document actually keeps.
        assertThat(names(defined))
                .describedAs("every declared header component is referenced by some response")
                .isEqualTo(referenced);
    }

    @Test
    void theErrorClassificationIsPublishedAsAnEnumRatherThanProse() throws Exception {
        JsonNode type = document().path("components").path("schemas").path("ApiError")
                .path("properties").path("type");
        List<String> values = new ArrayList<>();
        type.path("enum").forEach(value -> values.add(value.asText()));

        // `type` is the field §7.1's SDKs map onto their exception hierarchy. Generated from
        // ErrorType so the document cannot fall behind the enum, and asserted against the
        // enum here so the generation cannot silently stop happening.
        assertThat(values).containsExactlyElementsOf(
                Arrays.stream(ErrorType.values()).map(ErrorType::wireName).toList());
    }

    // ── The by-product ──────────────────────────────────────────────────────────────────

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
     * precondition for the fragment being worth merging.
     *
     * <p>The bytes written are the ones the service served, compared back rather than
     * assumed: the merged baseline should describe what the API actually returns, and a
     * re-serialization could differ in key order or whitespace without anyone noticing.
     */
    @Test
    void theFragmentIsWrittenForTheMergeStep() throws Exception {
        document();
        Path fragment = OpenApiFragments.write(serviceName(), documentJson);

        assertThat(Files.readString(fragment, StandardCharsets.UTF_8)).isEqualTo(documentJson);
    }

    // ── Helpers the subclasses reuse ────────────────────────────────────────────────────

    /** One operation: its path template, its verb, and the operation object. */
    @FunctionalInterface
    protected interface OperationVisitor {
        void visit(String path, String verb, JsonNode operation);
    }

    protected final void forEachOperation(OperationVisitor visitor) throws Exception {
        document().path("paths").properties().forEach(path ->
                path.getValue().properties().forEach(operation ->
                        visitor.visit(path.getKey(), operation.getKey(), operation.getValue())));
    }

    /**
     * {@code security: []} and "no security stated" are different things: an empty list is
     * an explicit opt-out, an absent key inherits the document's requirement. Only the first
     * makes an operation anonymous.
     */
    protected static boolean isUnauthenticated(JsonNode operation) {
        JsonNode security = operation.path("security");
        return security.isArray() && security.isEmpty();
    }

    protected final List<String> tagsOf(JsonNode operation) {
        List<String> tags = new ArrayList<>();
        operation.path("tags").forEach(tag -> tags.add(tag.asText()));
        return tags;
    }

    protected static Set<String> names(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        node.properties().forEach(entry -> names.add(entry.getKey()));
        return names;
    }
}
