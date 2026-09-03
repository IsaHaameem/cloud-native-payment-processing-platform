package dev.paymentflow.internal;

import dev.paymentflow.ApiException;
import dev.paymentflow.AuthenticationException;
import dev.paymentflow.IdempotencyException;
import dev.paymentflow.InvalidRequestException;
import dev.paymentflow.PaymentFlowException;
import dev.paymentflow.PaymentFlowException.Detail;
import dev.paymentflow.PermissionException;
import dev.paymentflow.RateLimitException;
import dev.paymentflow.model.ApiFieldError;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the exception for a response the platform refused.
 *
 * <p>{@code type} decides, not the status code: the platform separates {@code idempotency_error}
 * (retryable, a concurrent request holding the same key) from {@code invalid_request_error}
 * (never succeeds) even though both are 409, and mapping on status alone would throw that away.
 * Status is the fallback, and has to be — a 502 from a load balancer has no {@code type}, no
 * body, and often no JSON. An unrecognised {@code type} also falls back to status rather than
 * failing: §9 lets new error types ship without a new API revision.
 */
final class Errors {

    private Errors() {}

    @SuppressWarnings("unchecked")
    static PaymentFlowException fromResponse(Object body, int statusCode, String requestIdHeader,
                                             String correlationIdHeader, Double retryAfterSeconds, int attempts) {
        Map<String, Object> api = body instanceof Map ? (Map<String, Object>) body : Map.of();

        String type = string(api.get("type"));
        String message = string(api.get("message"));
        if (message == null || message.isEmpty()) {
            message = "The API returned HTTP " + statusCode + " with no error message.";
        }

        Detail detail = new Detail(
                statusCode,
                type,
                string(api.get("code")),
                string(api.get("param")),
                fieldErrors(api.get("errors")),
                coalesce(string(api.get("requestId")), requestIdHeader),
                coalesce(string(api.get("correlationId")), correlationIdHeader),
                string(api.get("docUrl")),
                attempts,
                retryAfterSeconds);

        return switch (type == null ? "" : type) {
            case "authentication_error" -> new AuthenticationException(message, detail, null);
            case "permission_error" -> new PermissionException(message, detail, null);
            case "invalid_request_error" -> new InvalidRequestException(message, detail, null);
            case "idempotency_error" -> new IdempotencyException(message, detail, null);
            case "rate_limit_error" -> new RateLimitException(message, detail, null);
            case "api_error" -> new ApiException(message, detail, null);
            default -> byStatus(statusCode, message, detail);
        };
    }

    private static PaymentFlowException byStatus(int status, String message, Detail detail) {
        if (status == 401) {
            return new AuthenticationException(message, detail, null);
        }
        if (status == 403) {
            return new PermissionException(message, detail, null);
        }
        if (status == 429) {
            return new RateLimitException(message, detail, null);
        }
        if (status >= 400 && status < 500) {
            return new InvalidRequestException(message, detail, null);
        }
        return new ApiException(message, detail, null);
    }

    @SuppressWarnings("unchecked")
    private static List<ApiFieldError> fieldErrors(Object raw) {
        if (!(raw instanceof List)) {
            return null;
        }
        List<ApiFieldError> out = new ArrayList<>();
        for (Object element : (List<Object>) raw) {
            if (element instanceof Map) {
                out.add(Json.toRecord(element, ApiFieldError.class));
            }
        }
        return out.isEmpty() ? null : List.copyOf(out);
    }

    private static String string(Object value) {
        return value instanceof String s ? s : null;
    }

    private static String coalesce(String a, String b) {
        return a != null ? a : b;
    }
}
