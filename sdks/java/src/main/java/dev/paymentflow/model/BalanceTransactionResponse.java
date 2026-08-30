package dev.paymentflow.model;

/**
 * One entry in your balance ledger. {@code amountMinor} is always positive — {@code direction}
 * ({@code DEBIT}/{@code CREDIT}, see {@link Vocabularies#BALANCE_TRANSACTION_RESPONSE_DIRECTION_VALUES})
 * carries the sign. {@code eventType} is the payment lifecycle event that produced it.
 */
public record BalanceTransactionResponse(
        String accountType,
        Long amountMinor,
        String createdAt,
        String currency,
        String direction,
        String eventType,
        String id,
        String mode,
        String object,
        String paymentId) {}
