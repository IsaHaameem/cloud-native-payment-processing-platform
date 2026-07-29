package com.paymentflow.transaction.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * One ledger entry as a merchant sees it (M19.4) — a single debit or credit against one
 * of their accounts, with the payment and lifecycle event that caused it.
 *
 * <p>Deliberately exposes the entry, not the whole balanced journal: the other leg of
 * every transaction touches the platform's own clearing account, which is not a
 * merchant's business to see. That makes this a genuine projection rather than a dump of
 * the ledger.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BalanceTransactionResponse(
        @Schema(description = "Unique identifier for this ledger entry.")
        UUID id,

        @Schema(description = "Always `balance_transaction`. The discriminator that identifies "
                + "this object out of context.", example = "balance_transaction")
        String object,

        @Schema(description = "The payment whose lifecycle produced this entry.")
        UUID paymentId,

        @Schema(description = """
                Which payment lifecycle event produced the entry — an authorization, a \
                capture, a refund, a void. This is the *why* of the movement.""",
                example = "payment.captured")
        String eventType,

        @Schema(description = """
                Which of your accounts moved. `MERCHANT_PENDING` holds authorized funds; \
                `MERCHANT_SETTLED` holds captured funds owed to you. The other leg of every \
                entry touches the platform's clearing account and is deliberately not \
                exposed.""",
                example = "MERCHANT_SETTLED")
        String accountType,

        @Schema(description = "Whether the entry added to (`CREDIT`) or removed from (`DEBIT`) "
                + "the account.", allowableValues = {"DEBIT", "CREDIT"}, example = "CREDIT")
        String direction,

        @Schema(description = "The amount moved, in the currency's minor unit. Always "
                + "positive — `direction` carries the sign.", example = "1000")
        long amountMinor,

        @Schema(description = "The three-letter ISO 4217 currency code.", example = "USD")
        String currency,

        @Schema(description = "Whether this entry is `test` or `live` data.",
                allowableValues = {"test", "live"}, example = "test")
        String mode,

        @Schema(description = "When the entry was posted, as RFC 3339.")
        Instant createdAt) {

    public static final String OBJECT_TYPE = "balance_transaction";
}
