package dev.paymentflow.internal;

import dev.paymentflow.RequestOptions;
import dev.paymentflow.model.Operations.OperationDescriptor;

import java.util.Map;

/**
 * What a resource method hands the transport. Assembled from a generated descriptor plus the
 * call's own values.
 *
 * @param operation  the operation being addressed
 * @param pathParams values for the {@code {...}} placeholders in the operation's path template
 * @param query      query parameters in wire spelling; {@code null} values are dropped, not sent empty
 * @param body       the request body value ({@code Map}/{@code List}/scalar), or {@code null}
 * @param options    per-call overrides, or {@code null}
 */
public record RequestSpec(
        OperationDescriptor operation,
        Map<String, String> pathParams,
        Map<String, Object> query,
        Object body,
        RequestOptions options) {

    public static RequestSpec of(OperationDescriptor operation) {
        return new RequestSpec(operation, Map.of(), Map.of(), null, null);
    }

    public RequestSpec path(String name, String value) {
        return new RequestSpec(operation, Map.of(name, value), query, body, options);
    }

    public RequestSpec query(Map<String, Object> query) {
        return new RequestSpec(operation, pathParams, query == null ? Map.of() : query, body, options);
    }

    public RequestSpec body(Object body) {
        return new RequestSpec(operation, pathParams, query, body, options);
    }

    public RequestSpec options(RequestOptions options) {
        return new RequestSpec(operation, pathParams, query, body, options);
    }
}
