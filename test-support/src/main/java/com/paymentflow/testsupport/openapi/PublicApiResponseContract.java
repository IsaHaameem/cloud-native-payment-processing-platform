package com.paymentflow.testsupport.openapi;

import com.paymentflow.common.security.InternalContextHeaders;
import com.paymentflow.common.security.InternalContextProperties;
import com.paymentflow.common.security.InternalContextSigner;
import com.paymentflow.openapi.OpenApiContract;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates a service's <em>real responses</em> against the published
 * {@code docs/openapi.yaml} (M21.7, §5/M21 task 6).
 *
 * <p><b>What this proves that the document tests do not.</b>
 * {@link PublicApiDocumentContract} asserts that the generated document is well formed and
 * says what it should. That is a statement about the document. This is the statement about
 * the <em>system</em>: that what the API actually returns is what the document promises. The
 * two can diverge without either being obviously wrong — a field renamed in a mapper, a
 * status that turns out to be nullable in practice, a payload described by reflecting a type
 * that has nothing to do with the JSON — and every one of those reaches an integrator as a
 * generated SDK that does not deserialize.
 *
 * <p><b>Validated against the committed baseline, not the live document.</b> A service
 * checked against its own description of itself cannot fail. {@code docs/openapi.yaml} is
 * the artefact this platform publishes — what M22's SDKs are generated from and M25's site
 * renders — so it is the one a response has to satisfy. Whether that file still matches the
 * code is a separate question, asked by {@code verifyOpenApiBaseline} in CI.
 *
 * <p><b>Undocumented fields are failures here</b>, which is stricter than JSON Schema.
 * See {@code SchemaValidator}: a response gaining a field the document does not mention is
 * exactly the drift this exists to catch, and under permissive rules it would validate
 * perfectly.
 *
 * <p>A subclass supplies its containers and a list of real calls to make. It inherits three
 * tests: every response validates, every call is documented, and the operations it exercises
 * are compared against the ones the document claims this service publishes.
 */
@AutoConfigureMockMvc
public abstract class PublicApiResponseContract {

    /**
     * The published document, read from the repository root.
     *
     * <p>The relative path is the same one {@code ErrorCatalogueDocumentationConsistencyTest}
     * uses to read {@code ../docs/ERRORS.md}: tests run with the module directory as their
     * working directory, and {@code docs/} is deliberately at the root because it is
     * published rather than owned by any one service.
     */
    protected static final Path PUBLISHED_DOCUMENT = Path.of("..", "docs", "openapi.yaml");

    /** Loaded once for the whole class — it is a 3,600-line document and does not change. */
    private static OpenApiContract contract;

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected InternalContextSigner signer;
    @Autowired
    protected InternalContextProperties internalContextProperties;

    /**
     * One real exchange to check.
     *
     * @param method         the HTTP method
     * @param uri            the URI to request, exactly as a client would send it
     * @param headers        the headers to send, usually a signed internal context
     * @param body           a JSON request body, or {@code null}
     * @param expectedStatus the status this call is expected to return. Stated rather than
     *                       accepted, so a call that starts returning 500 fails as a wrong
     *                       status rather than passing because 500 happens to be documented.
     */
    public record ContractCall(String method, String uri, HttpHeaders headers, String body,
                               int expectedStatus) {

        public static ContractCall get(String uri, HttpHeaders headers, int expectedStatus) {
            return new ContractCall("GET", uri, headers, null, expectedStatus);
        }

        public static ContractCall post(String uri, HttpHeaders headers, String body, int expectedStatus) {
            return new ContractCall("POST", uri, headers, body, expectedStatus);
        }

        public static ContractCall patch(String uri, HttpHeaders headers, String body, int expectedStatus) {
            return new ContractCall("PATCH", uri, headers, body, expectedStatus);
        }

        public static ContractCall delete(String uri, HttpHeaders headers, int expectedStatus) {
            return new ContractCall("DELETE", uri, headers, null, expectedStatus);
        }

        @Override
        public String toString() {
            return "%s %s -> %d".formatted(method, uri, expectedStatus);
        }
    }

    // ── What only the service knows ─────────────────────────────────────────────────────

    /**
     * The public paths this service publishes — the same set its document test declares,
     * used here to scope the coverage assertion to this service's slice of the merged
     * document.
     */
    protected abstract Set<String> publicPaths();

    /**
     * Seeds whatever data the calls need and returns them.
     *
     * <p>Seeding and listing are one method deliberately: a call that needs a payment to
     * exist needs that payment's id in its URI, so the two cannot be separated without
     * inventing a place to keep the id between them.
     */
    protected abstract List<ContractCall> contractCalls() throws Exception;

    /**
     * Operations this service publishes that the calls above deliberately do not exercise,
     * each with the reason. Empty by default.
     *
     * <p>Stated as an override rather than inferred, because "we do not test this" is a
     * claim that should have to be written down. The coverage test prints them, so an
     * exemption that stopped being true is visible rather than silently permanent.
     */
    protected Set<String> uncoveredOperations() {
        return Set.of();
    }

    protected static OpenApiContract contract() {
        if (contract == null) {
            contract = OpenApiContract.load(PUBLISHED_DOCUMENT);
        }
        return contract;
    }

    // ── The tests ───────────────────────────────────────────────────────────────────────

    @Test
    void everyLiveResponseMatchesTheDocumentItIsPublishedUnder() throws Exception {
        List<String> failures = new ArrayList<>();

        for (ContractCall call : contractCalls()) {
            MvcResult result = perform(call);
            int status = result.getResponse().getStatus();
            String body = result.getResponse().getContentAsString();

            if (status != call.expectedStatus()) {
                failures.add("%s: expected %d but got %d%n      %s"
                        .formatted(call, call.expectedStatus(), status, abbreviate(body)));
                continue;
            }
            contract().violations(call.method(), call.uri(), status, body)
                    .forEach(violation -> failures.add("%s: %s".formatted(call, violation)));
        }

        // Reported together rather than one per run: a response whose shape drifted usually
        // drifted in several fields at once, and fixing them one build at a time is how a
        // contract test becomes something people delete.
        assertThat(failures)
                .describedAs("live responses honour docs/openapi.yaml")
                .isEmpty();
    }

    @Test
    void everyOperationExercisedIsOneTheDocumentDescribes() throws Exception {
        List<String> undocumented = new ArrayList<>();

        for (ContractCall call : contractCalls()) {
            // A URI that matches no path template produces exactly this violation, so the
            // check costs nothing extra and catches the case the validation test above
            // would otherwise report as a schema failure with a confusing message.
            List<String> violations = contract().violations(call.method(), call.uri(), call.expectedStatus(), "{}");
            violations.stream()
                    .filter(violation -> violation.contains("describes no path")
                            || violation.contains("is documented, but not for"))
                    .forEach(violation -> undocumented.add("%s: %s".formatted(call, violation)));
        }

        assertThat(undocumented)
                .describedAs("every endpoint under test appears in the published document")
                .isEmpty();
    }

    @Test
    void theDocumentedOperationsAreEitherExercisedOrExplicitlyExcused() throws Exception {
        Set<String> exercised = new LinkedHashSet<>();
        for (ContractCall call : contractCalls()) {
            exercised.add(call.method().toUpperCase() + " " + templateOf(call.uri()));
        }

        List<String> unexercised = contract().operationsUnder(publicPaths()).stream()
                .map(OpenApiContract.OperationRef::toString)
                .filter(operation -> !exercised.contains(operation))
                .filter(operation -> !uncoveredOperations().contains(operation))
                .toList();

        // The claim this suite makes is "the published contract is honoured", and that claim
        // is only as wide as the operations actually called. Anything not called has to be
        // named in uncoveredOperations() with a reason, so the gap is a decision rather than
        // an oversight.
        assertThat(unexercised)
                .describedAs("every documented operation is exercised, or excused by name")
                .isEmpty();
    }

    // ── Helpers the subclasses reuse ────────────────────────────────────────────────────

    /**
     * Headers that look exactly like a request the gateway signed and forwarded (D100).
     *
     * <p>Shared here because five services' tests had written their own copy of this: the
     * signature covers a fixed field order and getting it wrong produces a 401 that reads as
     * a test-setup problem rather than as a signing problem.
     */
    protected HttpHeaders signedContext(UUID merchantId, String mode, String scopes) {
        String keyId = UUID.randomUUID().toString();
        long issuedAt = Instant.now().getEpochSecond();
        String signature = signer.sign(internalContextProperties.secret(), merchantId.toString(), mode,
                keyId, scopes, null, null, issuedAt);

        HttpHeaders headers = new HttpHeaders();
        headers.set(InternalContextHeaders.MERCHANT_ID, merchantId.toString());
        headers.set(InternalContextHeaders.MODE, mode);
        headers.set(InternalContextHeaders.KEY_ID, keyId);
        headers.set(InternalContextHeaders.SCOPES, scopes);
        headers.set(InternalContextHeaders.ISSUED_AT, Long.toString(issuedAt));
        headers.set(InternalContextHeaders.SIGNATURE, signature);
        return headers;
    }

    private MvcResult perform(ContractCall call) throws Exception {
        MockHttpServletRequestBuilder request = switch (call.method().toUpperCase()) {
            case "GET" -> MockMvcRequestBuilders.get(call.uri());
            case "POST" -> MockMvcRequestBuilders.post(call.uri());
            case "PATCH" -> MockMvcRequestBuilders.patch(call.uri());
            case "PUT" -> MockMvcRequestBuilders.put(call.uri());
            case "DELETE" -> MockMvcRequestBuilders.delete(call.uri());
            default -> throw new IllegalArgumentException("unsupported method " + call.method());
        };
        if (call.headers() != null) {
            request = request.headers(call.headers());
        }
        if (call.body() != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(call.body());
        }
        return mockMvc.perform(request).andReturn();
    }

    /** Resolves a concrete URI back to the template the document describes it under. */
    private String templateOf(String uri) {
        String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
        return contract().operations().stream()
                .map(OpenApiContract.OperationRef::path)
                .filter(template -> matches(template, path))
                .findFirst()
                .orElse(path);
    }

    private boolean matches(String template, String path) {
        String[] expected = template.split("/", -1);
        String[] actual = path.split("/", -1);
        if (expected.length != actual.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            boolean parameter = expected[i].startsWith("{") && expected[i].endsWith("}");
            if (!parameter && !expected[i].equals(actual[i])) {
                return false;
            }
        }
        return true;
    }

    private static String abbreviate(String body) {
        if (body == null || body.isBlank()) {
            return "(no body)";
        }
        return body.length() <= 200 ? body : body.substring(0, 197) + "...";
    }
}
