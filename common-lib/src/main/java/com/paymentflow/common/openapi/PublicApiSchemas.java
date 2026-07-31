package com.paymentflow.common.openapi;

import com.paymentflow.common.dto.error.ErrorType;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;

import java.util.Arrays;
import java.util.Map;

/**
 * Describes the schemas that appear in every service's fragment (M21.7, D154).
 *
 * <p><b>Why this is applied rather than annotated.</b> Three of these types live in
 * {@code common-dto}, which is deliberately framework-free — no Spring, no web, no
 * persistence, and by the same rule no swagger annotations. That constraint exists so the
 * module can be the one thing every service and every future SDK agrees on without dragging
 * a framework along, and adding {@code @Schema} to {@code ApiError} to make a document
 * prettier would be a poor trade. Applying the prose here, from the module that already owns
 * the document-level contract (D149), keeps {@code common-dto} clean and keeps the
 * descriptions in one place — which matters more than usual, because these fields appear in
 * all six fragments and M21.3's merge <em>refuses</em> to combine components that disagree.
 * Six annotated copies would have to be byte-identical forever; one customizer cannot drift.
 *
 * <p><b>The pagination envelopes are matched by name prefix.</b> springdoc names a generic
 * instantiation after its type argument — {@code CursorPagePaymentResponse},
 * {@code CursorPageEventResponse}, and so on — so there is no single schema to annotate even
 * if the type could be. The envelope's fields mean the same thing in every one of them,
 * which is what makes describing them by shape correct rather than merely convenient.
 */
public final class PublicApiSchemas {

    private PublicApiSchemas() {
    }

    /** {@code CursorPage<T>} — the envelope every M19 list returns (D107). */
    private static final Map<String, String> CURSOR_PAGE = Map.of(
            "object", "Always `list`. A constant discriminator, so a client deserializing a "
                    + "response can branch on a field rather than on the endpoint it called.",
            "data", "The objects on this page, most recent first.",
            "hasMore", "Whether more objects exist after this page. A cursor page reports no "
                    + "total count — that would cost a second full count query on every "
                    + "request — and this is the only question a paginating client needs.",
            "nextCursor", "Pass this as `starting_after` to fetch the next page. Absent when "
                    + "`hasMore` is false. Opaque and signed: treat it as a token, never "
                    + "parse or construct one.");

    /** {@code PageResponse<T>} — the older offset envelope two endpoints still use (D139). */
    private static final Map<String, String> PAGE_RESPONSE = Map.of(
            "content", "The objects on this page.",
            "page", "The zero-based index of this page.",
            "size", "How many objects each page holds.",
            "totalElements", "How many objects match the query in total.",
            "totalPages", "How many pages the result set spans.",
            "first", "Whether this is the first page.",
            "last", "Whether this is the last page.");

    /**
     * The error envelope (M21.4). Documented in full in {@code docs/ERRORS.md}.
     *
     * <p>{@code Map.ofEntries} rather than {@code Map.of}, which stops at ten pairs.
     */
    private static final Map<String, String> API_ERROR = Map.ofEntries(
            Map.entry("timestamp", "When the error occurred, as RFC 3339."),
            Map.entry("status", "The HTTP status code, repeated in the body so a logged "
                    + "response is self-contained."),
            Map.entry("type", "The error's classification, and **the field to branch on**. "
                    + "This is a small closed set an SDK maps to exception classes: "
                    + "`authentication_error`, `permission_error`, `invalid_request_error`, "
                    + "`idempotency_error`, `rate_limit_error`, `api_error`."),
            Map.entry("code", "A stable, machine-readable identifier for this specific "
                    + "failure, such as `PAYMENT_NOT_CAPTURABLE`. The set of codes grows by "
                    + "policy, which is why `type` and not this is what a client should "
                    + "switch on. Every code is catalogued in docs/ERRORS.md."),
            Map.entry("message", "A human-readable explanation. Safe to log; not intended to "
                    + "be shown to your customers, and never programmatically parsed."),
            Map.entry("param", "The single offending parameter, when there is exactly one. "
                    + "Multi-field validation failures use `errors` instead."),
            Map.entry("path", "The request path that produced the error."),
            Map.entry("requestId", "Identifies this one HTTP call. **Quote this in a support "
                    + "request** — it is on every log line and every row of your request log."),
            Map.entry("correlationId", "Identifies the whole distributed trace this call "
                    + "belongs to, which may span several services. Broader than `requestId`."),
            Map.entry("docUrl", "Where to read about this specific code."),
            Map.entry("errors", "Field-level validation failures, when the request failed "
                    + "validation in more than one place."));

    /** One entry of {@code ApiError.errors}. */
    private static final Map<String, String> API_FIELD_ERROR = Map.of(
            "field", "The request field that failed validation.",
            "message", "Why it failed. The rejected value is deliberately never echoed back.");

    /**
     * Applies the prose above to whichever of these schemas the service's document
     * contains.
     *
     * <p>Silent about schemas that are absent, deliberately: no service publishes all of
     * them — audit-service has no {@code PageResponse}, sandbox-service has no
     * {@code CursorPage} — and a customizer that insisted would fail five fragments to
     * describe one.
     */
    public static void describeSharedSchemas(OpenAPI document) {
        if (document.getComponents() == null || document.getComponents().getSchemas() == null) {
            return;
        }
        document.getComponents().getSchemas().forEach((name, schema) -> {
            if (name.startsWith("CursorPage")) {
                describe(schema, CURSOR_PAGE);
            } else if (name.startsWith("PageResponse")) {
                describe(schema, PAGE_RESPONSE);
            } else if ("ApiError".equals(name)) {
                describe(schema, API_ERROR);
                enumerateErrorTypes(schema);
            } else if ("ApiFieldError".equals(name)) {
                describe(schema, API_FIELD_ERROR);
            }
        });
    }

    /**
     * Publishes {@link ErrorType}'s vocabulary as a real {@code enum} on {@code ApiError.type}
     * (M22.0).
     *
     * <p>The prose above has always named the six values; nothing machine-readable did.
     * {@code ApiError.type} is a {@code String} on the record — deliberately, so that a
     * platform which learns a seventh classification cannot fail to deserialize its own
     * error — and springdoc can only describe what the field's Java type says, which is
     * {@code type: string}. That is exactly the field §7.1's SDKs map onto their exception
     * hierarchy, so every SDK would hand-maintain a copy of a list that already exists in
     * {@code common-dto}, checked against nothing.
     *
     * <p>Generated from {@code ErrorType.values()} rather than written out, so the document
     * and the enum cannot disagree: adding a classification updates the contract in the same
     * commit, and the SDKs' parity fixtures regenerate from it. Additive under §15 —
     * §9 requires clients to tolerate enum values they do not know, which is precisely what
     * makes publishing a closed vocabulary safe rather than a promise never to extend it.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void enumerateErrorTypes(Schema<?> apiError) {
        Map<String, Schema> properties = apiError.getProperties();
        if (properties == null) {
            return;
        }
        Schema type = properties.get("type");
        if (type == null) {
            return;
        }
        type.setEnum(Arrays.stream(ErrorType.values()).map(ErrorType::wireName).toList());
    }

    /**
     * Sets a description on each named property, leaving anything already described alone.
     *
     * <p>Not overwriting matters for the same reason it does in
     * {@link PublicApiErrorResponses#apply}: a service that has said something more specific
     * about a field knows something this class does not, and a blanket customizer that
     * clobbered it would quietly replace precise documentation with generic documentation.
     */
    @SuppressWarnings("rawtypes")
    private static void describe(Schema<?> schema, Map<String, String> descriptions) {
        Map<String, Schema> properties = schema.getProperties();
        if (properties == null) {
            return;
        }
        // Normalising this here is not cosmetic — it is what keeps the six fragments
        // mergeable. `ApiError` reaches the document by two different routes: springdoc
        // generates it by reflection wherever an operation names it in an `@ApiResponse`,
        // and `PublicApiErrorResponses` registers it through `ModelConverters` where nothing
        // does. The two agree on every property and disagree on exactly one thing — the
        // converter omits the object's own `type` — so the four services with per-operation
        // error responses published a schema the other two did not, and M21.3's merge
        // correctly refused to combine them. Found by that refusal rather than by review.
        // Both of swagger's type representations, because which one the 3.1 serializer
        // reads depends on how the schema was built, and this method is handed schemas from
        // two different builders.
        schema.setType("object");
        schema.addType("object");
        descriptions.forEach((property, description) -> {
            Schema<?> target = properties.get(property);
            if (target != null && (target.getDescription() == null || target.getDescription().isBlank())) {
                target.setDescription(description);
            }
        });
    }
}
