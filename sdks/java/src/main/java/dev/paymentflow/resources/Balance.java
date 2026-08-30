package dev.paymentflow.resources;

import dev.paymentflow.RequestOptions;
import dev.paymentflow.internal.Transport;
import dev.paymentflow.model.BalanceResponse;
import dev.paymentflow.model.Operations;

/** {@code client.balance()} — your current balance, per currency. */
public final class Balance extends Resource {

    public Balance(Transport transport) {
        super(transport);
    }

    /** Retrieves your balance. */
    public BalanceResponse retrieve(RequestOptions options) {
        return send(Operations.GET_BALANCE, null, null, null, opts(options), BalanceResponse.class);
    }

    public BalanceResponse retrieve() {
        return retrieve(null);
    }
}
