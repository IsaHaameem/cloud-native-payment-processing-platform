package com.paymentflow.payment.event;

import java.util.UUID;

/**
 * The JSON shape published for every payment lifecycle event. Deliberately local to
 * payment-service, not common-dto: consumers (transaction-service, audit-service, ...
 * from M6+) define their own copy matching this shape rather than importing this
 * class, so no consumer ever compiles against payment-service's internal model
 * (schema-per-service, D4, extended to messaging contracts).
 *
 * <p>{@code amountMinor} is the payment's total/original amount, constant across every
 * event for a given payment. {@code eventAmountMinor} is the amount this specific
 * transition actually moves: the full {@code amountMinor} for authorize/capture/void,
 * but the *incremental* amount for a refund — a consumer posting ledger entries (M6)
 * needs the delta, not the payment's running total, to post a partial refund correctly.
 *
 * <p>{@code merchantContactEmail}/{@code merchantWebhookUrl} are resolved once (the
 * same merchant-resolution call already made for every mutation) and embedded directly
 * into the event, so notification-service (M7) can deliver without a synchronous call
 * back to merchant-service (D43). {@code merchantWebhookUrl} is {@code null} whenever
 * the merchant hasn't configured one.
 */
public record PaymentEventPayload(
        UUID paymentId,
        UUID merchantId,
        long amountMinor,
        String currency,
        String status,
        String previousStatus,
        long eventAmountMinor,
        String merchantContactEmail,
        String merchantWebhookUrl) {
}
