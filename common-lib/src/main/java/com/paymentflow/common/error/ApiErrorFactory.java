package com.paymentflow.common.error;

import com.paymentflow.common.dto.error.ApiError;
import com.paymentflow.common.dto.error.ApiFieldError;

import java.time.Instant;
import java.util.List;

/**
 * Builds the {@link ApiError} envelope from an {@link ErrorCode} (M21.4).
 *
 * <p><b>Why assembly moved out of the handlers.</b> Two places produce error responses for
 * the public tier and they are written against different stacks: {@code
 * GlobalExceptionHandler} (servlet, in every service) and {@code GatewayErrorResponseWriter}
 * (reactive, in the gateway, and the only thing that answers a 401 or a 429 for a request
 * that never reached a service). Before M21.4 each built the record itself, which was
 * tolerable while the record had five fields that both knew. With {@code type}, {@code
 * docUrl}, {@code param} and {@code requestId} added, "both remember to populate all of
 * them" stops being a reasonable thing to rely on — and the failure would be invisible,
 * because each path's own tests would pass on its own output.
 *
 * <p>Deriving {@code type} and {@code docUrl} here rather than at the call sites is what
 * makes §5/M21's completion criterion — <em>every</em> error response carries a catalogued
 * code — true by construction instead of by review.
 */
public final class ApiErrorFactory {

    private ApiErrorFactory() {
    }

    /**
     * The full form.
     *
     * @param param  the single offending parameter, or {@code null}. Callers do not usually
     *               pass this: {@link #forFieldErrors} derives it, because the case where it
     *               is knowable is precisely the case where exactly one field failed.
     */
    public static ApiError create(ErrorCode errorCode, String message, String param, String path,
                                  String requestId, String correlationId, List<ApiFieldError> errors) {
        return new ApiError(
                Instant.now(),
                errorCode.httpStatus(),
                errorCode.type().wireName(),
                errorCode.code(),
                (message == null || message.isBlank()) ? errorCode.defaultMessage() : message,
                param,
                path,
                requestId,
                correlationId,
                errorCode.docUrl(),
                errors);
    }

    /** The common case: a code, a message, and no field-level detail. */
    public static ApiError create(ErrorCode errorCode, String message, String path,
                                  String requestId, String correlationId) {
        return create(errorCode, message, null, path, requestId, correlationId, List.of());
    }

    /**
     * A validation failure carrying its field errors, with {@code param} filled in when
     * exactly one field failed.
     *
     * <p>Only when there is exactly one, deliberately. {@code param} names <em>the</em>
     * offending parameter; picking the first of four would be a plausible-looking answer to
     * a question that has no single answer, and an SDK surfacing it in an exception message
     * would tell a developer to fix one field while three others were also wrong. When
     * several failed, {@code errors} is the complete answer and {@code param} is absent —
     * which, being {@code NON_NULL}, means the field simply is not there.
     */
    public static ApiError forFieldErrors(ErrorCode errorCode, String message, String path,
                                          String requestId, String correlationId,
                                          List<ApiFieldError> errors) {
        String param = (errors != null && errors.size() == 1) ? errors.getFirst().field() : null;
        return create(errorCode, message, param, path, requestId, correlationId,
                errors == null ? List.of() : errors);
    }
}
