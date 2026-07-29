package com.paymentflow.openapi;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The published contract, loaded from {@code docs/openapi.yaml} and asked whether a real
 * response honours it (M21.7, §5/M21 task 6).
 *
 * <p><b>Why the committed document rather than the one the service is serving.</b> They are
 * the same file only while {@code verifyOpenApiBaseline} is passing, and validating against
 * the live document would make that circular: the service would be checked against its own
 * description of itself, which cannot fail. {@code docs/openapi.yaml} is the artefact this
 * platform actually publishes — the one M22's SDKs are generated from and M25's site
 * renders — so it is the one a response has to satisfy. A service whose responses drift from
 * it fails here even if its own generated document drifted along with the code.
 *
 * <p><b>The path is resolved, not asserted.</b> A caller passes the URI it actually
 * requested and this class finds the path template that matches it. That makes "the endpoint
 * under test is described at all" part of what gets checked, rather than something the test
 * asserts about itself by naming the template it expects.
 */
public final class OpenApiContract {

    /** One documented operation. */
    public record OperationRef(String path, String method) {
        @Override
        public String toString() {
            return method.toUpperCase() + " " + path;
        }
    }

    private final JsonNode document;
    private final SchemaValidator validator;

    private OpenApiContract(JsonNode document) {
        this.document = document;
        this.validator = new SchemaValidator(document);
    }

    /**
     * Loads the published document.
     *
     * <p>A missing file is an error rather than a skipped test. The contract tests exist
     * precisely to fail when the published document and the code disagree, and one that
     * quietly passed because it could not find the document would be worse than no test at
     * all.
     */
    public static OpenApiContract load(Path document) {
        if (!Files.exists(document)) {
            throw new IllegalStateException("""
                    The published OpenAPI document was not found at %s.
                    Contract tests validate live responses against it; without it there is \
                    nothing to validate against. Run `gradlew mergeOpenApi`.""".formatted(document.toAbsolutePath()));
        }
        try {
            return new OpenApiContract(OpenApiYaml.read(Files.readString(document, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + document, e);
        }
    }

    /**
     * Checks one real exchange against the document.
     *
     * @param method       the HTTP method used
     * @param uri          the URI actually requested, query string and all
     * @param status       the status actually returned
     * @param responseBody the body actually returned; blank is treated as no body
     * @return every violation found, empty when the response honours the contract
     */
    public List<String> violations(String method, String uri, int status, String responseBody) {
        String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;

        String template = matchPath(path);
        if (template == null) {
            return List.of("the published document describes no path matching %s - either the endpoint is undocumented or its template is wrong"
                    .formatted(path));
        }
        JsonNode operation = document.path("paths").path(template).path(method.toLowerCase());
        if (operation.isMissingNode()) {
            return List.of("%s is documented, but not for %s".formatted(template, method.toUpperCase()));
        }

        JsonNode response = operation.path("responses").path(String.valueOf(status));
        if (response.isMissingNode()) {
            return List.of("%s %s returned %d, which the document does not describe"
                    .formatted(method.toUpperCase(), template, status));
        }

        JsonNode schema = response.path("content").path("application/json").path("schema");
        if (schema.isMissingNode()) {
            // A documented response with no JSON body — 204, or an error status the
            // operation documents without a payload. Nothing to validate, and nothing wrong.
            return List.of();
        }
        if (responseBody == null || responseBody.isBlank()) {
            return List.of("%s %s (%d) is documented as returning a JSON body and returned nothing"
                    .formatted(method.toUpperCase(), template, status));
        }

        JsonNode body;
        try {
            body = OpenApiYaml.read(responseBody);
        } catch (IllegalArgumentException e) {
            return List.of("%s %s (%d) did not return parseable JSON: %s"
                    .formatted(method.toUpperCase(), template, status, e.getMessage()));
        }
        return validator.validate(body, schema, "");
    }

    /**
     * Matches a concrete path against the document's templates.
     *
     * <p>Segment count first, then segment by segment, with {@code {name}} matching any one
     * non-empty segment. Exact matches win over templated ones, so
     * {@code /v1/test/simulations/active} resolves to itself rather than to a
     * {@code /v1/test/simulations/{id}} that might be added later.
     */
    private String matchPath(String path) {
        Set<String> templates = new LinkedHashSet<>();
        document.path("paths").properties().forEach(entry -> templates.add(entry.getKey()));
        if (templates.contains(path)) {
            return path;
        }
        String[] actual = path.split("/", -1);
        for (String template : templates) {
            String[] expected = template.split("/", -1);
            if (expected.length == actual.length && segmentsMatch(expected, actual)) {
                return template;
            }
        }
        return null;
    }

    private boolean segmentsMatch(String[] template, String[] actual) {
        for (int i = 0; i < template.length; i++) {
            boolean parameter = template[i].startsWith("{") && template[i].endsWith("}");
            if (parameter) {
                if (actual[i].isEmpty()) {
                    return false;
                }
            } else if (!template[i].equals(actual[i])) {
                return false;
            }
        }
        return true;
    }

    /** Every operation the published document describes, for coverage assertions. */
    public List<OperationRef> operations() {
        List<OperationRef> operations = new ArrayList<>();
        document.path("paths").properties().forEach(path ->
                path.getValue().properties().forEach(operation ->
                        operations.add(new OperationRef(path.getKey(), operation.getKey()))));
        return operations;
    }

    /** The operations under a path prefix — one service's slice of the merged document. */
    public List<OperationRef> operationsUnder(Set<String> paths) {
        return operations().stream().filter(operation -> paths.contains(operation.path())).toList();
    }

    public JsonNode document() {
        return document;
    }
}
