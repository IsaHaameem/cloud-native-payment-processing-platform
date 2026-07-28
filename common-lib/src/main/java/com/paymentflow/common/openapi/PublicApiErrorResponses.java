package com.paymentflow.common.openapi;

import com.paymentflow.common.error.CommonErrorCode;
import com.paymentflow.common.error.ErrorCode;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds the standard error responses to every published operation (M21.4, §5/M21 task 3).
 *
 * <p><b>Why a customizer and not annotations.</b> There are 31 published operations and
 * every one of them can return 401, 403, 429 and 500 for reasons that have nothing to do
 * with what the operation does — a revoked key, a missing scope, an exhausted bucket, a
 * failure. Writing {@code @ApiResponse} four times on each is 124 annotations that all say
 * the same thing, and the one that eventually gets missed is invisible: the document still
 * renders, the SDK generated from it simply has no type for that case. Applying them from
 * one place makes "documented on every operation" a property of the mechanism rather than
 * of whether anyone remembered.
 *
 * <p>This is the same reasoning D149 used for the {@code info} block, and it lands in
 * {@code common-lib} for the same reason: it describes the <em>API</em>, not any one
 * service. It also has a useful side effect on M21.3's merge — {@code ApiError} is now
 * generated into all six fragments identically, so the merge's deduplication path carries
 * the platform's most widely shared schema rather than only {@code SecretKey}.
 *
 * <p><b>What is deliberately not here.</b> Per-operation errors: a 404 on
 * {@code GET /v1/payments/{id}}, a 409 on a capture that has already happened. Those depend
 * on what the operation does, the service is the only thing that knows them, and stating
 * them belongs beside the mapping. This class covers the ones that are true of every
 * operation because they are true of the tier.
 */
public final class PublicApiErrorResponses {

    /**
     * The errors any authenticated operation can return regardless of what it does.
     *
     * <p>404 is absent deliberately even though many operations return one: on this tier a
     * 404 means "no such object, or not yours" (D-precedent: cross-mode and cross-merchant
     * access are 404, not 403, so a caller cannot probe for the existence of someone else's
     * data). That is a per-resource statement, not a universal one.
     */
    private static final List<ErrorCode> UNIVERSAL = List.of(
            CommonErrorCode.UNAUTHORIZED,
            CommonErrorCode.INSUFFICIENT_SCOPE,
            CommonErrorCode.RATE_LIMIT_EXCEEDED,
            CommonErrorCode.INTERNAL_ERROR);

    /** The schema name springdoc generates for {@code ApiError}; referenced, not inlined. */
    private static final String API_ERROR_REF = "#/components/schemas/ApiError";

    private PublicApiErrorResponses() {
    }

    /**
     * Generates the {@code ApiError} schema (and everything it references) into the
     * document's components.
     *
     * <p>Necessary because the responses above point at {@code ApiError} by {@code $ref}
     * without any handler method returning it — springdoc only generates schemas for types
     * it meets while scanning signatures, and no controller declares {@code ApiError} as a
     * return type. Without this the document would carry 124 references to a schema that is
     * not in it: still valid-looking, and every generator in M22 would produce either a
     * failure or an untyped error object.
     */
    public static void registerSchemas(io.swagger.v3.oas.models.OpenAPI document) {
        Map<String, Schema> schemas = io.swagger.v3.core.converter.ModelConverters.getInstance()
                .readAll(com.paymentflow.common.dto.error.ApiError.class);
        schemas.forEach((name, schema) -> {
            if (document.getComponents().getSchemas() == null
                    || !document.getComponents().getSchemas().containsKey(name)) {
                document.getComponents().addSchemas(name, schema);
            }
        });
    }

    /**
     * Adds each universal error to {@code operation}, leaving any response the operation
     * already declares untouched.
     *
     * <p>Not overwriting matters: an operation that documents its own 409 with a specific
     * message is saying something this class cannot know, and a blanket customizer that
     * clobbered it would silently replace precise documentation with generic documentation.
     */
    public static Operation apply(Operation operation) {
        ApiResponses responses = operation.getResponses();
        if (responses == null) {
            responses = new ApiResponses();
            operation.setResponses(responses);
        }
        boolean unauthenticated = isUnauthenticated(operation);
        for (ErrorCode code : UNIVERSAL) {
            if (unauthenticated && isCredentialRelated(code)) {
                continue;
            }
            String status = String.valueOf(code.httpStatus());
            if (!responses.containsKey(status)) {
                responses.addApiResponse(status, responseFor(code));
            }
        }
        return operation;
    }

    /**
     * True when the operation has opted out of the document-level security requirement with
     * an empty {@code @SecurityRequirements} — which on this platform means exactly one
     * endpoint, {@code GET /v1/test/cards} (§8.1).
     *
     * <p>{@code security: []} and "no security stated" are different things and swagger
     * models them differently: an empty list is an explicit override, {@code null} means the
     * operation inherits the document's requirement. Only the first is an opt-out.
     */
    private static boolean isUnauthenticated(Operation operation) {
        return operation.getSecurity() != null && operation.getSecurity().isEmpty();
    }

    /**
     * Whether this error can only happen to a request that presented a credential.
     *
     * <p>Documenting a 401 on an endpoint that requires no credential is the same class of
     * defect M21.2 fixed from the other direction — a document contradicting the running
     * system — and it would tell an SDK generator to produce credential-handling code for a
     * call that takes none. Keyed on {@link ErrorType} rather than status, so a future
     * authentication code lands on the right side of this without anyone remembering to
     * update a list of status numbers.
     *
     * <p>429 and 500 are deliberately still documented: unauthenticated traffic is rate
     * limited by IP (D24), and anything can fail.
     */
    private static boolean isCredentialRelated(ErrorCode code) {
        return code.type() == com.paymentflow.common.dto.error.ErrorType.AUTHENTICATION_ERROR
                || code.type() == com.paymentflow.common.dto.error.ErrorType.PERMISSION_ERROR;
    }

    private static ApiResponse responseFor(ErrorCode code) {
        return new ApiResponse()
                .description(code.defaultMessage())
                .content(new Content().addMediaType("application/json", new MediaType()
                        .schema(new Schema<>().$ref(API_ERROR_REF))
                        .example(exampleFor(code))));
    }

    /**
     * A real body rather than a schema alone. §9.2 is explicit that every documented
     * response shows a response body, and an error example is the one most worth showing:
     * it is how a developer learns that {@code type} is the field to branch on and that
     * {@code requestId} is the value to quote in a support request.
     *
     * <p>A {@link LinkedHashMap} so the example's key order matches the record's, and an
     * {@link Example} rather than a raw map so swagger renders it as an example rather than
     * trying to read it as a schema.
     */
    private static Object exampleFor(ErrorCode code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", "2026-07-28T12:34:56.789Z");
        body.put("status", code.httpStatus());
        body.put("type", code.type().wireName());
        body.put("code", code.code());
        body.put("message", code.defaultMessage());
        body.put("path", "/v1/payments");
        body.put("requestId", "req_9f2c1e7a4b8d");
        body.put("correlationId", "3f9a1c2e-77b4-4a1d-9c3e-1d2b4f6a8c05");
        body.put("docUrl", code.docUrl());
        return body;
    }
}
