package com.paymentflow.transaction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A ledger account with a running balance. {@code @Version} makes concurrent postings
 * to the same account (e.g. every event touches the shared {@code PLATFORM_CLEARING}
 * account) optimistically locked — a losing concurrent update fails fast with
 * {@link org.springframework.orm.ObjectOptimisticLockingFailureException}, which
 * {@code LedgerService} retries against a freshly-read balance.
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, updatable = false, length = 30)
    private AccountType accountType;

    @Column(name = "owner_id", updatable = false)
    private UUID ownerId;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Account() {
        // Required by JPA.
    }

    private Account(AccountType accountType, UUID ownerId, String currency) {
        this.accountType = accountType;
        this.ownerId = ownerId;
        this.currency = currency;
        this.balanceMinor = 0;
    }

    public static Account open(AccountType accountType, UUID ownerId, String currency) {
        return new Account(accountType, ownerId, currency);
    }

    /** Posts a debit or credit, adjusting the running balance per this account's debit/credit normalcy. */
    public void apply(Direction direction, long amountMinor) {
        boolean increasesBalance = (direction == Direction.DEBIT) == accountType.isDebitNormal();
        this.balanceMinor += increasesBalance ? amountMinor : -amountMinor;
    }

    public UUID getId() {
        return id;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getCurrency() {
        return currency;
    }

    public long getBalanceMinor() {
        return balanceMinor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
