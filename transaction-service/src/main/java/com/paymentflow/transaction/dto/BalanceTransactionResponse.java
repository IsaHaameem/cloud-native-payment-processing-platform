package com.paymentflow.transaction.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

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
        UUID id,
        String object,
        UUID paymentId,
        String eventType,
        String accountType,
        String direction,
        long amountMinor,
        String currency,
        String mode,
        Instant createdAt) {

    public static final String OBJECT_TYPE = "balance_transaction";
}
