package com.paymentflow.transaction.event;

import java.util.UUID;

/**
 * Local mirror of payment-service's {@code PaymentEventPayload} JSON shape (D36 —
 * consumers define their own copy rather than importing the producer's class, so no
 * consumer's compile-time dependencies couple to payment-service's internal model).
 *
 * <p>{@code eventAmountMinor} is the amount this specific transition moved — the full
 * {@code amountMinor} for authorize/capture/void, the incremental amount for a
 * (possibly partial) refund — exactly what a ledger posting needs.
 */
public record PaymentLedgerEventPayload(
        UUID paymentId,
        UUID merchantId,
        long amountMinor,
        String currency,
        String status,
        String previousStatus,
        long eventAmountMinor) {
}
