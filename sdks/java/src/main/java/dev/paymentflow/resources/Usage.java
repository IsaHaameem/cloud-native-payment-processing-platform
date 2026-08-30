package dev.paymentflow.resources;

import dev.paymentflow.RequestOptions;
import dev.paymentflow.internal.Transport;
import dev.paymentflow.model.Operations;
import dev.paymentflow.model.UsageSummaryResponse;

/** {@code client.usage()} — your API usage, metered per UTC day. */
public final class Usage extends Resource {

    public Usage(Transport transport) {
        super(transport);
    }

    /** Retrieves usage over {@code [from, to]} as calendar dates ({@code YYYY-MM-DD}). */
    public UsageSummaryResponse retrieve(String from, String to, RequestOptions options) {
        return send(Operations.GET_USAGE, null,
                query().put("from", from).put("to", to).build(), null, opts(options), UsageSummaryResponse.class);
    }

    public UsageSummaryResponse retrieve() {
        return retrieve(null, null, null);
    }
}
