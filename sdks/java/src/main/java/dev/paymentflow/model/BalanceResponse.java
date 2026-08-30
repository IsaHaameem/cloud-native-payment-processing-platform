package dev.paymentflow.model;

import java.util.List;

/**
 * Your current balance, one entry per currency you hold a balance in. A currency you have never
 * transacted in is absent rather than reported as zero. {@code object} is always {@code balance}.
 */
public record BalanceResponse(List<CurrencyBalance> balances, String object) {}
