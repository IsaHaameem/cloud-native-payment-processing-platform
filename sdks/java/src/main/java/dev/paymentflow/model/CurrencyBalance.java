package dev.paymentflow.model;

/**
 * Your balance in one currency, in that currency's minor unit.
 *
 * <p>{@code availableMinor} is money captured and owed to you, net of refunds.
 * {@code pendingMinor} is money authorized but not yet captured — not yours yet, since an
 * authorization can still be voided or expire.
 */
public record CurrencyBalance(Long availableMinor, String currency, Long pendingMinor) {}
