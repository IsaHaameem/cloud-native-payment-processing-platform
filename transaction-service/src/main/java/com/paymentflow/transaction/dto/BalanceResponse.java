package com.paymentflow.transaction.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A merchant's balance across every currency they hold, in one mode (M19.4).
 *
 * <p>Two figures per currency, and the distinction is the whole point: {@code pending}
 * is money authorized but not yet captured, {@code available} is money captured and owed
 * to the merchant. They come from two separate ledger accounts
 * ({@code MERCHANT_PENDING}, {@code MERCHANT_SETTLED}), so this is a projection of the
 * double-entry ledger rather than a number kept alongside it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BalanceResponse(String object, List<CurrencyBalance> balances) {

    public static final String OBJECT_TYPE = "balance";

    public BalanceResponse {
        balances = (balances == null) ? List.of() : List.copyOf(balances);
    }

    /**
     * @param pendingMinor   authorized, not yet captured
     * @param availableMinor captured and owed to the merchant
     */
    public record CurrencyBalance(String currency, long pendingMinor, long availableMinor) {
    }
}
