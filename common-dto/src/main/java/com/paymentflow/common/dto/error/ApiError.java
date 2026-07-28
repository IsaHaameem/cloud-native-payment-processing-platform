package com.paymentflow.common.dto.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Standard, immutable error envelope returned by every service for any non-2xx
 * response.
 *
 * <p>The {@code code} is a stable, machine-readable identifier that clients can
 * branch on without parsing human-readable messages. {@code correlationId} ties the
 * response back to the distributed trace for support and debugging.
 *
 * <p><b>M21.4 extended this record with {@code type}, {@code param}, {@code requestId} and
 * {@code docUrl}</b> (§5/M21 task 3). Every addition is additive and {@code NON_NULL}, so a
 * client written against the V1 shape sees exactly what it saw before plus fields it is
 * required to ignore (§7.1's forward-compatibility rule). Nothing was renamed, removed, or
 * retyped — under §4.10 that keeps this a change that ships unversioned.
 *
 * <p>What each of the four adds, since "more fields" is not a justification:
 * <ul>
 *   <li>{@code type} — the small, stable classification an SDK maps to an exception class,
 *       as opposed to {@code code}, whose set is allowed to grow. See {@link ErrorType}.</li>
 *   <li>{@code param} — the single offending parameter, when there is exactly one. The
 *       {@code errors} list is still the complete answer for multi-field validation; this
 *       is the one an SDK can put in an exception message without formatting a list.</li>
 *   <li>{@code requestId} — the identifier a developer quotes in a support request. It is
 *       already in every log line (M13's MDC) and on every row of the M20 request log;
 *       until now the one place it did not appear was the response that made someone want
 *       it. Distinct from {@code correlationId}, which spans the whole distributed trace:
 *       {@code requestId} names one HTTP call.</li>
 *   <li>{@code docUrl} — where to read about this specific code. An error message has to be
 *       short; the explanation of why a capture can exceed no authorization does not fit in
 *       one, and a link costs nothing to carry.</li>
 * </ul>
 *
 * <p>Assembly lives in {@code ApiErrorFactory} in common-lib rather than here, so the
 * servlet services and the reactive gateway cannot drift into producing different shapes.
 * This record stays a dumb carrier.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        Instant timestamp,
        int status,
        String type,
        String code,
        String message,
        String param,
        String path,
        String requestId,
        String correlationId,
        String docUrl,
        List<ApiFieldError> errors) {

    public ApiError {
        // Defensive copy keeps the record deeply immutable regardless of caller.
        errors = (errors == null) ? List.of() : List.copyOf(errors);
    }

    /**
     * Creates an error without field-level details (the common case).
     *
     * <p>Retained with its original five-argument shape so the V1 call sites that only ever
     * knew a status, a code and a message still compile and still produce a valid envelope.
     * It leaves {@code type} and {@code docUrl} null, which is why nothing on the public
     * tier uses it — {@code ApiErrorFactory} does, from an {@code ErrorCode} that knows
     * both.
     */
    public static ApiError of(int status, String code, String message, String path, String correlationId) {
        return new ApiError(Instant.now(), status, null, code, message, null, path, null, correlationId,
                null, List.of());
    }

    /** Creates an error carrying field-level validation failures. */
    public static ApiError of(int status, String code, String message, String path, String correlationId,
                              List<ApiFieldError> errors) {
        return new ApiError(Instant.now(), status, null, code, message, null, path, null, correlationId,
                null, errors);
    }
}
