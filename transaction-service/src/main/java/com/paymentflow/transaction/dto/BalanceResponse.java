package com.paymentflow.transaction.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

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
public record BalanceResponse(
        @Schema(description = "Always `balance`. The discriminator that identifies this "
                + "object out of context.", example = "balance")
        String object,

        @Schema(description = "One entry per currency you hold a balance in. A currency you "
                + "have never transacted in is absent rather than reported as zero.")
        List<CurrencyBalance> balances) {

    public static final String OBJECT_TYPE = "balance";

    public BalanceResponse {
        balances = (balances == null) ? List.of() : List.copyOf(balances);
    }

    /**
     * @param pendingMinor   authorized, not yet captured
     * @param availableMinor captured and owed to the merchant
     */
    public record CurrencyBalance(
            @Schema(description = "The three-letter ISO 4217 currency code.", example = "USD")
            String currency,

            @Schema(description = """
                    Money authorized but not yet captured, in the currency's minor unit. \
                    Not yours yet — an authorization can still be voided or expire.""",
                    example = "0")
            long pendingMinor,

            @Schema(description = """
                    Money captured and owed to you, in the currency's minor unit, net of \
                    refunds.""",
                    example = "125000")
            long availableMinor) {
    }
}
