package dev.paymentflow.resources;

import dev.paymentflow.CursorPage;
import dev.paymentflow.RequestOptions;
import dev.paymentflow.internal.Transport;
import dev.paymentflow.model.BalanceTransactionResponse;
import dev.paymentflow.model.Operations;

import java.util.Map;

/** {@code client.balanceTransactions()} — the entries that moved your balance. */
public final class BalanceTransactions extends Resource {

    public BalanceTransactions(Transport transport) {
        super(transport);
    }

    /** Filters for {@link #list}. */
    public static final class ListParams {

        Long limit;
        String createdAfter;
        String createdBefore;

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
    }

    public static ListParams listParams() {
        return new ListParams();
    }

    /** Lists balance transactions, most recent first. The result paginates transparently. */
    public CursorPage<BalanceTransactionResponse> list(ListParams params, RequestOptions options) {
        ListParams p = params == null ? new ListParams() : params;
        Map<String, Object> query = query()
                .put("limit", p.limit)
                .put("created_after", p.createdAfter)
                .put("created_before", p.createdBefore)
                .build();
        return listCursor(Operations.LIST_BALANCE_TRANSACTIONS, query, opts(options), BalanceTransactionResponse.class);
    }

    public CursorPage<BalanceTransactionResponse> list() {
        return list(null, null);
    }
}
