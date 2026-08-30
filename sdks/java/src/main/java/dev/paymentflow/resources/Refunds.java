package dev.paymentflow.resources;

import dev.paymentflow.CursorPage;
import dev.paymentflow.RequestOptions;
import dev.paymentflow.internal.Transport;
import dev.paymentflow.model.Operations;
import dev.paymentflow.model.RefundResponse;

import java.util.Map;

/** {@code client.refunds()} — reading refunds. They are created through {@code payments().refund()}. */
public final class Refunds extends Resource {

    public Refunds(Transport transport) {
        super(transport);
    }

    /** Filters for {@link #list}. All optional; wire spellings. */
    public static final class ListParams {

        Long limit;
        String payment;
        String status;
        String createdAfter;
        String createdBefore;
        Map<String, String> metadata;

        public ListParams limit(long limit) {
            this.limit = limit;
            return this;
        }

        public ListParams payment(String paymentId) {
            this.payment = paymentId;
            return this;
        }

        public ListParams status(String status) {
            this.status = status;
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

        public ListParams metadata(Map<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }
    }

    public static ListParams listParams() {
        return new ListParams();
    }

    /** Retrieves one refund. */
    public RefundResponse retrieve(String id, RequestOptions options) {
        return send(Operations.GET_REFUND, Map.of("id", id), null, null, opts(options), RefundResponse.class);
    }

    public RefundResponse retrieve(String id) {
        return retrieve(id, null);
    }

    /** Lists your refunds, most recent first. The result paginates transparently. */
    public CursorPage<RefundResponse> list(ListParams params, RequestOptions options) {
        ListParams p = params == null ? new ListParams() : params;
        Map<String, Object> query = query()
                .put("limit", p.limit)
                .put("payment", p.payment)
                .put("status", p.status)
                .put("created_after", p.createdAfter)
                .put("created_before", p.createdBefore)
                .put("metadata", p.metadata)
                .build();
        return listCursor(Operations.LIST_REFUNDS, query, opts(options), RefundResponse.class);
    }

    public CursorPage<RefundResponse> list() {
        return list(null, null);
    }
}
