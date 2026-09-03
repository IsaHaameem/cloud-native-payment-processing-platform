package dev.paymentflow.resources;

import dev.paymentflow.CursorPage;
import dev.paymentflow.RequestOptions;
import dev.paymentflow.internal.Transport;
import dev.paymentflow.model.EventResponse;
import dev.paymentflow.model.Operations;

import java.util.Map;

/** {@code client.events()} — the event log behind your webhooks. */
public final class Events extends Resource {

    public Events(Transport transport) {
        super(transport);
    }

    /** Filters for {@link #list}. */
    public static final class ListParams {

        Long limit;
        String type;
        String createdAfter;
        String createdBefore;

        public ListParams limit(long limit) {
            this.limit = limit;
            return this;
        }

        public ListParams type(String type) {
            this.type = type;
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
    }

    public static ListParams listParams() {
        return new ListParams();
    }

    /** Retrieves one event. Its {@code id} matches the {@code id} in the webhook body for it. */
    public EventResponse retrieve(String id, RequestOptions options) {
        return send(Operations.GET_EVENT, Map.of("id", id), null, null, opts(options), EventResponse.class);
    }

    public EventResponse retrieve(String id) {
        return retrieve(id, null);
    }

    /** Lists events, most recent first. The result paginates transparently. */
    public CursorPage<EventResponse> list(ListParams params, RequestOptions options) {
        ListParams p = params == null ? new ListParams() : params;
        Map<String, Object> query = query()
                .put("limit", p.limit)
                .put("type", p.type)
                .put("created_after", p.createdAfter)
                .put("created_before", p.createdBefore)
                .build();
        return listCursor(Operations.LIST_EVENTS, query, opts(options), EventResponse.class);
    }

    public CursorPage<EventResponse> list() {
        return list(null, null);
    }
}
