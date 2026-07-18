package com.paymentflow.analytics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * One row per (merchant, currency) — a running read-model aggregate updated as each
 * payment lifecycle event is consumed. Every event for a given merchant+currency
 * contends on this same row, so it carries an optimistic-lock {@code @Version} and is
 * updated inside {@code AnalyticsService}'s whole-transaction retry loop, mirroring
 * transaction-service's shared-account contention pattern (M6).
 */
@Entity
@Table(name = "merchant_payment_stats")
public class MerchantPaymentStats {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "created_count", nullable = false)
    private long createdCount;

    @Column(name = "authorized_count", nullable = false)
    private long authorizedCount;

    @Column(name = "captured_count", nullable = false)
    private long capturedCount;

    @Column(name = "refunded_count", nullable = false)
    private long refundedCount;

    @Column(name = "voided_count", nullable = false)
    private long voidedCount;

    @Column(name = "total_captured_amount_minor", nullable = false)
    private long totalCapturedAmountMinor;

    @Column(name = "total_refunded_amount_minor", nullable = false)
    private long totalRefundedAmountMinor;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected MerchantPaymentStats() {
        // Required by JPA.
    }

    private MerchantPaymentStats(UUID merchantId, String currency) {
        this.merchantId = merchantId;
        this.currency = currency;
    }

    public static MerchantPaymentStats open(UUID merchantId, String currency) {
        return new MerchantPaymentStats(merchantId, currency);
    }

    public void incrementCreated() {
        createdCount++;
    }

    public void incrementAuthorized() {
        authorizedCount++;
    }

    public void incrementCaptured(long amountMinor) {
        capturedCount++;
        totalCapturedAmountMinor += amountMinor;
    }

    public void incrementRefunded(long amountMinor) {
        refundedCount++;
        totalRefundedAmountMinor += amountMinor;
    }

    public void incrementVoided() {
        voidedCount++;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getCurrency() {
        return currency;
    }

    public long getCreatedCount() {
        return createdCount;
    }

    public long getAuthorizedCount() {
        return authorizedCount;
    }

    public long getCapturedCount() {
        return capturedCount;
    }

    public long getRefundedCount() {
        return refundedCount;
    }

    public long getVoidedCount() {
        return voidedCount;
    }

    public long getTotalCapturedAmountMinor() {
        return totalCapturedAmountMinor;
    }

    public long getTotalRefundedAmountMinor() {
        return totalRefundedAmountMinor;
    }

    public long getVersion() {
        return version;
    }
}
