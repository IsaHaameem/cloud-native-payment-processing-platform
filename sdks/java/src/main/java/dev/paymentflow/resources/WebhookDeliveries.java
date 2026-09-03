package dev.paymentflow.resources;

import dev.paymentflow.OffsetPage;
import dev.paymentflow.RequestOptions;
import dev.paymentflow.internal.Transport;
import dev.paymentflow.model.Operations;
import dev.paymentflow.model.WebhookDeliveryResponse;

import java.util.List;
import java.util.Map;

/**
 * {@code client.webhookDeliveries()} — what happened when an event was delivered.
 *
 * <p>Offset-paginated, not cursor-paginated: D139's deliberate exception. The SDK reports what
 * the endpoint returns.
 */
public final class WebhookDeliveries extends Resource {

    public WebhookDeliveries(Transport transport) {
        super(transport);
    }

    /** Filters for {@link #list}. */
    public static final class ListParams {

        Integer page;
        Integer size;
        List<String> sort;

        public ListParams page(int page) {
            this.page = page;
            return this;
        }

        public ListParams size(int size) {
            this.size = size;
            return this;
        }

        /** Sort instructions, such as {@code createdAt,desc}. */
        public ListParams sort(List<String> sort) {
            this.sort = sort;
            return this;
        }
    }

    public static ListParams listParams() {
        return new ListParams();
    }

    /** Retrieves one delivery, including its attempts. */
    public WebhookDeliveryResponse retrieve(String id, RequestOptions options) {
        return send(Operations.GET_WEBHOOK_DELIVERY, Map.of("id", id), null, null, opts(options),
                WebhookDeliveryResponse.class);
    }

    public WebhookDeliveryResponse retrieve(String id) {
        return retrieve(id, null);
    }

    /** Lists deliveries. The result paginates transparently. */
    public OffsetPage<WebhookDeliveryResponse> list(ListParams params, RequestOptions options) {
        ListParams p = params == null ? new ListParams() : params;
        Map<String, Object> query = query()
                .put("size", p.size)
                .put("sort", p.sort)
                .build();
        return listOffset(Operations.LIST_WEBHOOK_DELIVERIES, null, query, opts(options),
                WebhookDeliveryResponse.class);
    }

    public OffsetPage<WebhookDeliveryResponse> list() {
        return list(null, null);
    }

    /** Re-sends a delivery. Returns the new delivery, not the original. */
    public WebhookDeliveryResponse replay(String id, RequestOptions options) {
        return send(Operations.REPLAY_WEBHOOK_DELIVERY, Map.of("id", id), null, null, opts(options),
                WebhookDeliveryResponse.class);
    }

    public WebhookDeliveryResponse replay(String id) {
        return replay(id, null);
    }
}
