package dev.paymentflow.resources;

import dev.paymentflow.RequestOptions;
import dev.paymentflow.internal.Transport;
import dev.paymentflow.model.AnalyticsSummaryResponse;
import dev.paymentflow.model.Operations;

/**
 * {@code client.analytics()} — payment activity summarized over a window.
 *
 * <p>The window is RFC 3339 instants here, and calendar dates for {@link Usage} — the platform's
 * spelling, not this SDK's. Usage is metered per UTC day, so a window with a time in it would
 * imply a precision the meter does not have.
 */
public final class Analytics extends Resource {

    public Analytics(Transport transport) {
        super(transport);
    }

    /** Summarizes payment activity over {@code [from, to]} (RFC 3339 instants), with hourly buckets. */
    public AnalyticsSummaryResponse retrievePaymentSummary(String from, String to, RequestOptions options) {
        return send(Operations.GET_PAYMENT_ANALYTICS, null,
                query().put("from", from).put("to", to).build(), null, opts(options),
                AnalyticsSummaryResponse.class);
    }

    public AnalyticsSummaryResponse retrievePaymentSummary() {
        return retrievePaymentSummary(null, null, null);
    }
}
