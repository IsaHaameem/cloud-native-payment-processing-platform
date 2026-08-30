package dev.paymentflow.resources;

import dev.paymentflow.CursorPage;
import dev.paymentflow.RequestOptions;
import dev.paymentflow.internal.Transport;
import dev.paymentflow.model.Operations;
import dev.paymentflow.model.RequestLogResponse;

import java.util.Map;

/**
 * {@code client.requestLogs()} — your API calls, as the platform recorded them. Each row is keyed
 * by the {@code requestId} this SDK reports on every response and every thrown exception, so a
 * call you captured in your own logs can be looked up here directly.
 */
public final class RequestLogs extends Resource {

    public RequestLogs(Transport transport) {
        super(transport);
    }

    /** Filters for {@link #list}. */
    public static final class ListParams {

        Long limit;
        String createdAfter;
        String createdBefore;
        Long statusCode;
        String method;

        public ListParams limit(long limit) {
            this.limit = limit;
            return this;
        }

        public ListParams createdAfter(String rfc3339) {
            this.createdAfter = rfc3339;
            return this;
        }

        public ListParams createdBefore(String rfc3339) {
            this.createdBefore = rfc3339;
            return this;
        }

        /** Only calls that returned this status. */
        public ListParams statusCode(long statusCode) {
            this.statusCode = statusCode;
            return this;
        }

        /** Only calls made with this HTTP method. */
        public ListParams method(String method) {
            this.method = method;
            return this;
        }
    }

    public static ListParams listParams() {
        return new ListParams();
    }

    /** Lists your API calls, most recent first. The result paginates transparently. */
    public CursorPage<RequestLogResponse> list(ListParams params, RequestOptions options) {
        ListParams p = params == null ? new ListParams() : params;
        Map<String, Object> query = query()
                .put("limit", p.limit)
                .put("created_after", p.createdAfter)
                .put("created_before", p.createdBefore)
                .put("status_code", p.statusCode)
                .put("method", p.method)
                .build();
        return listCursor(Operations.LIST_REQUEST_LOGS, query, opts(options), RequestLogResponse.class);
    }

    public CursorPage<RequestLogResponse> list() {
        return list(null, null);
    }
}
