package dev.paymentflow.resources;

import dev.paymentflow.CursorPage;
import dev.paymentflow.RequestOptions;
import dev.paymentflow.internal.Transport;
import dev.paymentflow.model.Operations;
import dev.paymentflow.model.PaymentResponse;

import java.util.Map;

/**
 * {@code client.payments()} — the payment lifecycle.
 *
 * <p>Seven methods, one per published operation, and no eighth. There is no {@code createAndCapture}
 * convenience even though it would be two obvious lines, because a method that performs two
 * chargeable calls behind one name is a method whose failure modes an integrator cannot reason
 * about: the second call failing leaves an authorized payment they did not know they had. Every
 * method here is exactly one HTTP request.
 */
public final class Payments extends Resource {

    public Payments(Transport transport) {
        super(transport);
    }

    /** What {@link #create} accepts. Build with {@link #params()}. */
    public static final class CreateParams {

        String currency;
        Long amountMinor;
        String description;
        String paymentMethodToken;
        Map<String, String> metadata;

        private CreateParams() {}

        /**
         * The amount to charge, an integer in the currency's minor unit ({@code 1000} in
         * {@code USD} is $10.00). Required — the platform's field has a {@code @Positive} bound
         * and rejects a body without it every time, even though the published schema does not
         * list it under {@code required} (D170).
         */
        public CreateParams amountMinor(long amountMinor) {
            this.amountMinor = amountMinor;
            return this;
        }

        /** The three-letter ISO 4217 currency code, such as {@code USD}. Required. */
        public CreateParams currency(String currency) {
            this.currency = currency;
            return this;
        }

        /** An arbitrary description for your own records, up to 500 characters. */
        public CreateParams description(String description) {
            this.description = description;
            return this;
        }

        /**
         * A payment-method token to authorize against. In test mode, pass one from
         * {@code client.testHelpers().listCards()} to choose the outcome.
         */
        public CreateParams paymentMethodToken(String token) {
            this.paymentMethodToken = token;
            return this;
        }

        /** Your own key-value pairs. Never interpreted by this platform. */
        public CreateParams metadata(Map<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }
    }

    public static CreateParams params() {
        return new CreateParams();
    }

    /** What {@link #list} accepts. All optional. Wire spellings, so they match the docs. */
    public static final class ListParams {

        Long limit;
        String status;
        String currency;
        Long amountMin;
        Long amountMax;
        String createdAfter;
        String createdBefore;
        String expand;
        Map<String, String> metadata;

        public ListParams limit(long limit) {
            this.limit = limit;
            return this;
        }

        public ListParams status(String status) {
            this.status = status;
            return this;
        }

        public ListParams currency(String currency) {
            this.currency = currency;
            return this;
        }

        public ListParams amountMin(long amountMin) {
            this.amountMin = amountMin;
            return this;
        }

        public ListParams amountMax(long amountMax) {
            this.amountMax = amountMax;
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

        /** The only expandable relation on this resource is {@code refunds}. */
        public ListParams expand(String expand) {
            this.expand = expand;
            return this;
        }

        /** Containment filter, spelled {@code metadata[key]=value}. Every named key must match. */
        public ListParams metadata(Map<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }
    }

    public static ListParams listParams() {
        return new ListParams();
    }

    /** What {@link #refund} accepts. All optional: an empty body refunds the full amount. */
    public static final class RefundParams {

        Long amountMinor;
        String reason;
        Map<String, String> metadata;

        public RefundParams amountMinor(long amountMinor) {
            this.amountMinor = amountMinor;
            return this;
        }

        public RefundParams reason(String reason) {
            this.reason = reason;
            return this;
        }

        public RefundParams metadata(Map<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }
    }

    public static RefundParams refundParams() {
        return new RefundParams();
    }

    // ── operations ─────────────────────────────────────────────────────────────────────────

    /** Creates a payment. Generates an {@code Idempotency-Key} unless you supply one in {@code options}. */
    public PaymentResponse create(CreateParams params, RequestOptions options) {
        Object body = body()
                .put("amountMinor", params.amountMinor)
                .put("currency", params.currency)
                .put("description", params.description)
                .put("paymentMethodToken", params.paymentMethodToken)
                .put("metadata", params.metadata)
                .build();
        return send(Operations.CREATE_PAYMENT, null, null, body, opts(options), PaymentResponse.class);
    }

    public PaymentResponse create(CreateParams params) {
        return create(params, null);
    }

    /** Retrieves one payment. Pass {@code expand="refunds"} to include its refunds. */
    public PaymentResponse retrieve(String id, String expand, RequestOptions options) {
        return send(Operations.GET_PAYMENT, Map.of("id", id),
                query().put("expand", expand).build(), null, opts(options), PaymentResponse.class);
    }

    public PaymentResponse retrieve(String id) {
        return retrieve(id, null, null);
    }

    /** Lists your payments, most recent first. The result paginates transparently. */
    public CursorPage<PaymentResponse> list(ListParams params, RequestOptions options) {
        ListParams p = params == null ? new ListParams() : params;
        Map<String, Object> query = query()
                .put("limit", p.limit)
                .put("status", p.status)
                .put("currency", p.currency)
                .put("amount_min", p.amountMin)
                .put("amount_max", p.amountMax)
                .put("created_after", p.createdAfter)
                .put("created_before", p.createdBefore)
                .put("expand", p.expand)
                .put("metadata", p.metadata)
                .build();
        return listCursor(Operations.LIST_PAYMENTS, query, opts(options), PaymentResponse.class);
    }

    public CursorPage<PaymentResponse> list() {
        return list(null, null);
    }

    /** Authorizes a created payment, reserving the funds. */
    public PaymentResponse authorize(String id, RequestOptions options) {
        return send(Operations.AUTHORIZE_PAYMENT, Map.of("id", id), null, null, opts(options), PaymentResponse.class);
    }

    public PaymentResponse authorize(String id) {
        return authorize(id, null);
    }

    /** Captures an authorized payment, moving the funds. */
    public PaymentResponse capture(String id, RequestOptions options) {
        return send(Operations.CAPTURE_PAYMENT, Map.of("id", id), null, null, opts(options), PaymentResponse.class);
    }

    public PaymentResponse capture(String id) {
        return capture(id, null);
    }

    /**
     * Refunds a captured payment, in full or in part.
     *
     * <p>Returns the <b>payment</b>, not the refund — the refund is the newest entry in the
     * payment's {@code refunds} array. That is what the endpoint returns; reshaping it here would
     * mean a second request or a guess about which element is the new one.
     */
    public PaymentResponse refund(String id, RefundParams params, RequestOptions options) {
        RefundParams p = params == null ? new RefundParams() : params;
        Object body = body()
                .put("amountMinor", p.amountMinor)
                .put("reason", p.reason)
                .put("metadata", p.metadata)
                .build();
        return send(Operations.REFUND_PAYMENT, Map.of("id", id), null, body, opts(options), PaymentResponse.class);
    }

    public PaymentResponse refund(String id) {
        return refund(id, null, null);
    }

    /** Voids an authorized payment, releasing the funds without capturing them. */
    public PaymentResponse voidPayment(String id, RequestOptions options) {
        return send(Operations.VOID_PAYMENT, Map.of("id", id), null, null, opts(options), PaymentResponse.class);
    }

    public PaymentResponse voidPayment(String id) {
        return voidPayment(id, null);
    }
}
