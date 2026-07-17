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
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        String correlationId,
        List<ApiFieldError> errors) {

    public ApiError {
        // Defensive copy keeps the record deeply immutable regardless of caller.
        errors = (errors == null) ? List.of() : List.copyOf(errors);
    }

    /** Creates an error without field-level details (the common case). */
    public static ApiError of(int status, String code, String message, String path, String correlationId) {
        return new ApiError(Instant.now(), status, code, message, path, correlationId, List.of());
    }

    /** Creates an error carrying field-level validation failures. */
    public static ApiError of(int status, String code, String message, String path, String correlationId,
                              List<ApiFieldError> errors) {
        return new ApiError(Instant.now(), status, code, message, path, correlationId, errors);
    }
}
