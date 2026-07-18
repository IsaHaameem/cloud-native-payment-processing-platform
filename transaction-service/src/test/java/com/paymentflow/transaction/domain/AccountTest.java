package com.paymentflow.transaction.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountTest {

    @Test
    void debitIncreasesADebitNormalAccountsBalance() {
        Account clearing = Account.open(AccountType.PLATFORM_CLEARING, null, "USD");

        clearing.apply(Direction.DEBIT, 1000);

        assertThat(clearing.getBalanceMinor()).isEqualTo(1000);
    }

    @Test
    void creditDecreasesADebitNormalAccountsBalance() {
        Account clearing = Account.open(AccountType.PLATFORM_CLEARING, null, "USD");
        clearing.apply(Direction.DEBIT, 1000);

        clearing.apply(Direction.CREDIT, 400);

        assertThat(clearing.getBalanceMinor()).isEqualTo(600);
    }

    @Test
    void creditIncreasesACreditNormalAccountsBalance() {
        Account merchantPending = Account.open(AccountType.MERCHANT_PENDING, UUID.randomUUID(), "USD");

        merchantPending.apply(Direction.CREDIT, 1000);

        assertThat(merchantPending.getBalanceMinor()).isEqualTo(1000);
    }

    @Test
    void debitDecreasesACreditNormalAccountsBalance() {
        Account merchantSettled = Account.open(AccountType.MERCHANT_SETTLED, UUID.randomUUID(), "USD");
        merchantSettled.apply(Direction.CREDIT, 1000);

        merchantSettled.apply(Direction.DEBIT, 300);

        assertThat(merchantSettled.getBalanceMinor()).isEqualTo(700);
    }

    @Test
    void newAccountStartsAtZeroBalance() {
        Account account = Account.open(AccountType.MERCHANT_PENDING, UUID.randomUUID(), "EUR");

        assertThat(account.getBalanceMinor()).isZero();
    }
}
