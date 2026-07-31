package com.paymentflow.common.openapi;

import com.paymentflow.common.correlation.CorrelationConstants;
import com.paymentflow.common.dto.http.PublicApiHeaders;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Describes the transport headers the public tier speaks, on every operation that can carry
 * them (M22.0).
 *
 * <p><b>Why this exists.</b> M21 made the <em>payloads</em> of {@code /v1} machine-readable
 * and left the transport around them in prose. That was survivable while the only readers
 * were humans; M22's SDKs are the first readers that cannot infer anything. The single most
 * important behaviour either SDK implements — retry a 429 after waiting the interval the
 * platform named, rather than after a delay it guessed — reads
 * {@link PublicApiHeaders#RETRY_AFTER} and {@link PublicApiHeaders#RATE_LIMIT_RESET}, and
 * before this class the published contract did not mention either. An SDK written against a
 * document that omits them is an SDK whose backoff is folklore.
 *
 * <p><b>Why the header set differs per status, and why that is not a detail.</b> These
 * headers are written by gateway filters, and the gateway's filter order is load-bearing
 * (§7): a rejection written by an early filter never reaches the later ones. So a 401 —
 * refused by {@code ApiKeyAuthenticationWebFilter} at order +20 — genuinely carries neither
 * the rate-limit headers (+30) nor the version headers (+40), and a 429 — refused at +30 —
 * carries the rate-limit headers but not the version ones. Documenting all eight headers
 * uniformly on all 130-odd responses would have been three lines shorter and wrong in a way
 * an integrator only discovers by reading a header that is never there. What is encoded
 * below is the filter chain, which is the only thing that can answer the question.
 *
 * <p><b>An {@code OpenApiCustomizer} rather than an {@code OperationCustomizer}.</b> The
 * responses this has to annotate include the four universal errors, which
 * {@link PublicApiErrorResponses} adds from an {@code OperationCustomizer} of its own.
 * springdoc runs every {@code OperationCustomizer} while it builds an operation and every
 * {@code OpenApiCustomizer} against the finished document, so working at the document level
 * is what makes "after the error responses exist" a fact rather than an ordering that two
 * unrelated beans have to agree on. It also means the six services' configurations did not
 * have to grow a third bean each.
 *
 * <p>Headers are emitted as {@code $ref}s into {@code components.headers} rather than
 * inlined. One definition per header, deduplicated across the six fragments by M21.3's merge
 * exactly as {@code ApiError} and {@code SecretKey} already are — and, because
 * {@code OpenApiDiff} treats a changed shared component as breaking for every reference at
 * once, a silent edit to what {@code RateLimit-Reset} means cannot slip through as one
 * response's local detail.
 */
public final class PublicApiTransport {

    /**
     * Statuses the gateway writes <em>before</em> {@code ApiKeyRateLimitWebFilter} (+30), so
     * no quota accounting has happened and none can be reported. 401 and 403 on this tier are
     * both {@code ApiKeyAuthenticationWebFilter}'s (+20): scope enforcement for the key path
     * is gateway-only, so a 403 never originates downstream.
     */
    private static final Set<String> REFUSED_BEFORE_RATE_LIMITING = Set.of("401", "403");

    /**
     * Statuses written before {@code ApiVersionWebFilter} (+40). The rate limiter's own 429
     * joins the two above: a request refused for being over its allowance is never version-
     * resolved, so echoing a revision on it would be a claim about work that did not happen.
     */
    private static final Set<String> REFUSED_BEFORE_VERSIONING = Set.of("401", "403", "429");

    private static final String RATE_LIMIT_CONDITION =
            "Present on responses to API-key traffic once the request has been measured "
                    + "against its allowance; absent on a response refused before that "
                    + "point, and on unauthenticated traffic, which is limited by address "
                    + "instead.";

    private PublicApiTransport() {
    }

    /**
     * Registers the header components and references them from every operation and response
     * that can carry them.
     */
    public static void apply(OpenAPI document) {
        if (document == null || document.getPaths() == null) {
            return;
        }
        registerHeaderComponents(document);
        document.getPaths().values().forEach(pathItem ->
                pathItem.readOperations().forEach(PublicApiTransport::describeOperation));
    }

    // ── The definitions ─────────────────────────────────────────────────────────────────

    /**
     * The ten transport headers, defined once each.
     *
     * <p>A {@link LinkedHashMap} built by a supplier per entry rather than a constant map of
     * {@code Header} instances: swagger's models are mutable, and one shared instance
     * referenced from six fragments is one instance any of them could edit for all of them.
     */
    private static Map<String, Supplier<Header>> headerDefinitions() {
        Map<String, Supplier<Header>> headers = new LinkedHashMap<>();

        headers.put(CorrelationConstants.CORRELATION_ID_HEADER, () -> header(
                "Identifies the whole distributed trace this call belongs to. Echoed from "
                        + "your request when you send one and generated when you do not, so a "
                        + "client can correlate its own logs with PaymentFlow's. This is the "
                        + "value to quote in a support request when the response carried no "
                        + "body to read `requestId` from.",
                // Deliberately left word-for-word as M22.0 published it. Rewording a shared
                // component is a breaking change to every `$ref` that points at it, and the
                // gate says so — an improvement to one sentence is not worth an entry in the
                // accepted-breaking file, which §14's debt item 4 already warns can rot into
                // a blanket suppression. The sentence is still true; it is merely no longer
                // the only answer now that X-Request-Id is on every response.
                new StringSchema().format("uuid")));

        headers.put(CorrelationConstants.REQUEST_ID_HEADER, () -> header(
                "Identifies this one HTTP call, and keys the matching row of "
                        + "`GET /v1/request_logs`. **Quote this in a support request.** Echoed "
                        + "from your request when you send one and generated when you do not. "
                        + "Present on every response, including successful ones — `requestId` "
                        + "in an error body is the same value, for the case where you have the "
                        + "body and not the headers.",
                new StringSchema().format("uuid")));

        headers.put(PublicApiHeaders.VERSION, () -> header(
                "The dated API revision that answered this request, resolved as request "
                        + "header, then your pinned revision, then the current one. Always "
                        + "present on a response that reached the API rather than being "
                        + "refused at the edge.",
                new StringSchema().example("2026-08-01")));

        headers.put(PublicApiHeaders.RATE_LIMIT_LIMIT, () -> header(
                "Your daily request quota for this mode. " + RATE_LIMIT_CONDITION,
                integerSchema()));

        headers.put(PublicApiHeaders.RATE_LIMIT_REMAINING, () -> header(
                "How many requests remain in the current quota window. " + RATE_LIMIT_CONDITION,
                integerSchema()));

        headers.put(PublicApiHeaders.RATE_LIMIT_RESET, () -> header(
                "Seconds until the quota window resets, at 00:00 UTC. Describes the daily "
                        + "quota rather than the per-second burst bucket: the bucket's reset "
                        + "is always under a second away and would be worthless to plan "
                        + "against. " + RATE_LIMIT_CONDITION,
                integerSchema()));

        headers.put(PublicApiHeaders.RETRY_AFTER, () -> header(
                "Seconds to wait before retrying. **Prefer this over any backoff you "
                        + "compute** — it is the interval the platform will actually accept "
                        + "the request again, and for an exhausted daily quota it is the time "
                        + "remaining until 00:00 UTC rather than a few seconds. Branch on the "
                        + "error `code` to tell the two causes apart: `RATE_LIMIT_EXCEEDED` "
                        + "clears in seconds, `DAILY_QUOTA_EXCEEDED` at the end of the day.",
                integerSchema()));

        headers.put(PublicApiHeaders.DEPRECATION, () -> header(
                "`true` when the revision that answered has been superseded. Sent per RFC "
                        + "8594 as the flag form rather than a date, because the moment a "
                        + "revision became deprecated for you is not something this platform "
                        + "records per merchant. Absent on the current revision.",
                new StringSchema().example("true")));

        headers.put(PublicApiHeaders.SUNSET, () -> header(
                "The RFC 9110 date after which the superseded revision stops being served. "
                        + "Accompanies `Deprecation`; absent on the current revision.",
                new StringSchema().example("Sun, 01 Aug 2027 00:00:00 GMT")));

        headers.put(PublicApiHeaders.LINK, () -> header(
                "An RFC 8288 link to the versioning documentation, with "
                        + "`rel=\"deprecation\"`. What makes `Deprecation` actionable rather "
                        + "than merely alarming; absent on the current revision.",
                new StringSchema()
                        .example("<https://docs.paymentflow.dev/versioning>; rel=\"deprecation\"")));

        return headers;
    }

    /**
     * A whole-number header value.
     *
     * <p>Both of swagger's type representations, and this is not belt-and-braces: the 3.1
     * serializer reads the {@code types} set, so a schema built with {@code setType} alone
     * renders with its {@code format} and no {@code type} at all — a valid-looking header
     * declaration that says nothing about what kind of value it carries. Found by reading the
     * generated baseline rather than the annotation, which §18 warning 4 is the standing
     * instruction to do.
     */
    private static Schema<?> integerSchema() {
        Schema<Integer> schema = new Schema<>();
        schema.setType("integer");
        schema.addType("integer");
        schema.setFormat("int64");
        return schema;
    }

    private static Header header(String description, Schema<?> schema) {
        // Never `required`. Every one of these is conditional on something — the caller
        // being authenticated, the revision being superseded, the request being refused —
        // and a header marked required that is legitimately absent is a validator failure
        // reported against the platform rather than against the document.
        return new Header().description(description).schema(schema).required(false);
    }

    private static void registerHeaderComponents(OpenAPI document) {
        if (document.getComponents() == null) {
            document.setComponents(new Components());
        }
        Components components = document.getComponents();
        headerDefinitions().forEach((name, definition) -> {
            if (components.getHeaders() == null || !components.getHeaders().containsKey(name)) {
                components.addHeaders(name, definition.get());
            }
        });
    }

    // ── Application ─────────────────────────────────────────────────────────────────────

    private static void describeOperation(Operation operation) {
        addRequestHeaders(operation);
        if (operation.getResponses() == null) {
            return;
        }
        operation.getResponses().forEach((status, response) ->
                addResponseHeaders(status, response));
    }

    /**
     * The two headers a caller may send that are not specific to any one operation.
     *
     * <p>{@code Idempotency-Key} is deliberately not among them: it applies to mutations
     * only, payment-service documents it where it belongs, and adding it here would put it on
     * every {@code GET} in the platform.
     */
    private static void addRequestHeaders(Operation operation) {
        addParameterIfAbsent(operation, PublicApiHeaders.VERSION, new StringSchema().example("2026-08-01"),
                "Pin this one request to a dated API revision. Overrides the revision your "
                        + "account is pinned to; omit it to use that pin, or the current "
                        + "revision if you have none. An unrecognised value is rejected with "
                        + "`400 UNSUPPORTED_API_VERSION` rather than silently ignored.");

        addParameterIfAbsent(operation, CorrelationConstants.CORRELATION_ID_HEADER,
                new StringSchema().format("uuid"),
                "Your own identifier for this operation, echoed back on the response and "
                        + "recorded on every log line it produces. Send one to join your logs "
                        + "to PaymentFlow's; omit it and one is generated for you.");
    }

    private static void addParameterIfAbsent(Operation operation, String name, Schema<?> schema,
                                             String description) {
        if (operation.getParameters() == null) {
            operation.setParameters(new ArrayList<>());
        }
        boolean declared = operation.getParameters().stream()
                .anyMatch(parameter -> name.equals(parameter.getName())
                        && "header".equals(parameter.getIn()));
        if (declared) {
            // An operation that documented this header itself knows something specific; the
            // same reason PublicApiErrorResponses does not overwrite a declared response.
            return;
        }
        operation.getParameters().add(new Parameter()
                .in("header")
                .name(name)
                .required(false)
                .description(description)
                .schema(schema));
    }

    private static void addResponseHeaders(String status, ApiResponse response) {
        for (String name : headersFor(status)) {
            if (response.getHeaders() == null || !response.getHeaders().containsKey(name)) {
                response.addHeaderObject(name, reference(name));
            }
        }
    }

    /**
     * Which headers a response with this status can carry — the gateway's filter order,
     * written down.
     */
    private static List<String> headersFor(String status) {
        List<String> names = new ArrayList<>();

        // CorrelationIdWebFilter runs at HIGHEST_PRECEDENCE, before anything can refuse a
        // request, so these two are on literally every response the platform produces.
        names.add(CorrelationConstants.CORRELATION_ID_HEADER);
        names.add(CorrelationConstants.REQUEST_ID_HEADER);

        if (!REFUSED_BEFORE_RATE_LIMITING.contains(status)) {
            names.add(PublicApiHeaders.RATE_LIMIT_LIMIT);
            names.add(PublicApiHeaders.RATE_LIMIT_REMAINING);
            names.add(PublicApiHeaders.RATE_LIMIT_RESET);
        }
        if (!REFUSED_BEFORE_VERSIONING.contains(status)) {
            names.add(PublicApiHeaders.VERSION);
            names.add(PublicApiHeaders.DEPRECATION);
            names.add(PublicApiHeaders.SUNSET);
            names.add(PublicApiHeaders.LINK);
        }
        if ("429".equals(status)) {
            // The only status that carries it, and the only one an SDK reads it from.
            names.add(PublicApiHeaders.RETRY_AFTER);
        }
        return names;
    }

    private static Header reference(String name) {
        // The full pointer rather than the bare name: swagger expands a `$ref` with no `/`
        // or `.` in it against a default section, and depending on that would make the
        // rendered document a function of which swagger version is on the classpath.
        return new Header().$ref("#/components/headers/" + name);
    }
}
