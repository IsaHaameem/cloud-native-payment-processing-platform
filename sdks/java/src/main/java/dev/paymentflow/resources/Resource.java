package dev.paymentflow.resources;

import dev.paymentflow.CursorPage;
import dev.paymentflow.OffsetPage;
import dev.paymentflow.RequestOptions;
import dev.paymentflow.internal.Json;
import dev.paymentflow.internal.PageFetch;
import dev.paymentflow.internal.Paginator;
import dev.paymentflow.internal.RequestSpec;
import dev.paymentflow.internal.Transport;
import dev.paymentflow.model.Operations.OperationDescriptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * What every resource namespace shares. Resource classes are thin on purpose: a method names an
 * operation, hands over its parameters, and returns what the API returned. Everything that could
 * differ between them — how a path is filled in, which query parameters are legal, when an
 * idempotency key is generated, what gets retried — lives in the transport, so adding an
 * endpoint cannot accidentally add a behaviour.
 */
public abstract class Resource {

    protected final Transport transport;

    protected Resource(Transport transport) {
        this.transport = transport;
    }

    /** One call, deserialized to {@code type}. */
    protected <T> T send(OperationDescriptor op, Map<String, String> path, Map<String, Object> query,
                         Object body, RequestOptions options, Class<T> type) {
        RequestSpec spec = new RequestSpec(op, path == null ? Map.of() : path,
                query == null ? Map.of() : query, body, options);
        Transport.Result result = transport.request(spec);
        return Json.toRecord(result.data(), type);
    }

    /** One call whose response is a bare JSON array, deserialized element-wise to {@code element}. */
    protected <T> List<T> sendList(OperationDescriptor op, Map<String, String> path, Map<String, Object> query,
                                   RequestOptions options, Class<T> element) {
        RequestSpec spec = new RequestSpec(op, path == null ? Map.of() : path,
                query == null ? Map.of() : query, null, options);
        Object data = transport.request(spec).data();
        if (!(data instanceof List<?> list)) {
            return List.of();
        }
        List<T> out = new ArrayList<>();
        for (Object raw : list) {
            out.add(Json.toRecord(raw, element));
        }
        return List.copyOf(out);
    }

    /** One call whose response body is discarded (a 204, or a fire-and-forget). */
    protected void sendVoid(OperationDescriptor op, Map<String, String> path, Object body, RequestOptions options) {
        transport.request(new RequestSpec(op, path == null ? Map.of() : path, Map.of(), body, options));
    }

    /** A cursor-paginated list. The filters are captured so every later page uses the same ones. */
    protected <T> CursorPage<T> listCursor(OperationDescriptor op, Map<String, Object> query,
                                           RequestOptions options, Class<T> element) {
        Function<Object, T> mapper = raw -> Json.toRecord(raw, element);
        PageFetch fetch = locator -> {
            Map<String, Object> page = new LinkedHashMap<>(query);
            if (locator != null) {
                page.put("starting_after", locator);
            }
            return transport.request(new RequestSpec(op, Map.of(), page, null, options));
        };
        return Paginator.cursor(fetch.fetch(null), fetch, mapper);
    }

    /** An offset-paginated list — the two endpoints D139 left on the older envelope. */
    protected <T> OffsetPage<T> listOffset(OperationDescriptor op, Map<String, String> path,
                                           Map<String, Object> query, RequestOptions options, Class<T> element) {
        Function<Object, T> mapper = raw -> Json.toRecord(raw, element);
        Map<String, String> pathParams = path == null ? Map.of() : path;
        PageFetch fetch = locator -> {
            Map<String, Object> page = new LinkedHashMap<>(query);
            page.put("page", locator == null ? 0 : locator);
            return transport.request(new RequestSpec(op, pathParams, page, null, options));
        };
        return Paginator.offset(fetch.fetch(null), fetch, mapper);
    }

    // ── small builders, so the resource methods read as data ────────────────────────────────

    protected static RequestOptions opts(RequestOptions options) {
        return options == null ? RequestOptions.NONE : options;
    }

    /** A query map that drops null values, in insertion order. */
    protected static QueryBuilder query() {
        return new QueryBuilder();
    }

    protected static final class QueryBuilder {

        private final Map<String, Object> map = new LinkedHashMap<>();

        public QueryBuilder put(String key, Object value) {
            if (value != null) {
                map.put(key, value);
            }
            return this;
        }

        public Map<String, Object> build() {
            return map;
        }
    }

    /** A body map that drops null values — the same rule as {@code body_of} in the Python SDK. */
    protected static BodyBuilder body() {
        return new BodyBuilder();
    }

    protected static final class BodyBuilder {

        private final Map<String, Object> map = new LinkedHashMap<>();

        public BodyBuilder put(String key, Object value) {
            if (value != null) {
                map.put(key, value);
            }
            return this;
        }

        public Map<String, Object> build() {
            return map;
        }
    }
}
