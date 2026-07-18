package com.paymentflow.transaction.domain;

/**
 * The three account kinds this ledger tracks. {@code debitNormal} governs which
 * {@link Direction} increases an account's balance — standard double-entry
 * bookkeeping: asset-like accounts are debit-normal, liability-like accounts are
 * credit-normal.
 */
public enum AccountType {
    /** Singleton per currency (no owner) — the platform's own clearing/suspense account; asset-like. */
    PLATFORM_CLEARING(true),
    /** One per (merchant, currency) — funds authorized but not yet captured; liability-like. */
    MERCHANT_PENDING(false),
    /** One per (merchant, currency) — funds captured and owed to the merchant; liability-like. */
    MERCHANT_SETTLED(false);

    private final boolean debitNormal;

    AccountType(boolean debitNormal) {
        this.debitNormal = debitNormal;
    }

    public boolean isDebitNormal() {
        return debitNormal;
    }
}
