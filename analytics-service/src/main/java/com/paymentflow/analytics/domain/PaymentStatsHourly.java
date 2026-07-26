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
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * One hour of payment activity for a (merchant, currency, mode) — the time series behind
 * M19's analytics API (M19.6).
 *
 * <p>{@link MerchantPaymentStats} is a running total and answers "how much, ever"; it
 * cannot answer "how much this afternoon", because that information was never recorded.
 * This is the same counters, bucketed, and it exists alongside rather than instead of the
 * totals: the totals are complete back to M7, while this series necessarily starts when
 * the table does.
 *
 * <p>Carries the same optimistic-lock {@code @Version} and is updated inside the same
 * whole-transaction retry loop as the running totals, because it has the same contention
 * shape — every event for one merchant in one hour lands on one row.
 *
 * <p>{@code failedCount} is new here and absent from the running totals. It became
 * visible as a gap the moment anything actually read these numbers: a success *rate*
 * needs a denominator that includes failures.
 */
@Entity
@Table(name = "payment_stats_hourly")
public class PaymentStatsHourly {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(nullable = false, updatable = false, length = 4)
    private String mode;

    @Column(name = "bucket_start", nullable = false, updatable = false)
    private Instant bucketStart;

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

    @Column(name = "failed_count", nullable = false)
    private long failedCount;

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

    protected PaymentStatsHourly() {
        // Required by JPA.
    }

    private PaymentStatsHourly(UUID merchantId, String currency, String mode, Instant bucketStart) {
        this.merchantId = merchantId;
        this.currency = currency;
        this.mode = mode;
        this.bucketStart = bucketStart;
    }

    public static PaymentStatsHourly open(UUID merchantId, String currency, String mode, Instant bucketStart) {
        return new PaymentStatsHourly(merchantId, currency, mode, truncate(bucketStart));
    }

    /**
     * The bucket an instant belongs to: the containing hour, in UTC.
     *
     * <p>Public and used by the query side as well as the write side, so "which bucket is
     * this?" has exactly one answer. Two implementations of hour-truncation that disagree
     * at a boundary would put an event in one bucket and look for it in another.
     */
    public static Instant truncate(Instant instant) {
        return instant.truncatedTo(ChronoUnit.HOURS);
    }

    public void incrementCreated() {
        this.createdCount++;
    }

    public void incrementAuthorized() {
        this.authorizedCount++;
    }

    public void incrementCaptured(long amountMinor) {
        this.capturedCount++;
        this.totalCapturedAmountMinor += amountMinor;
    }

    public void incrementRefunded(long amountMinor) {
        this.refundedCount++;
        this.totalRefundedAmountMinor += amountMinor;
    }

    public void incrementVoided() {
        this.voidedCount++;
    }

    public void incrementFailed() {
        this.failedCount++;
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

    public String getMode() {
        return mode;
    }

    public Instant getBucketStart() {
        return bucketStart;
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

    public long getFailedCount() {
        return failedCount;
    }

    public long getTotalCapturedAmountMinor() {
        return totalCapturedAmountMinor;
    }

    public long getTotalRefundedAmountMinor() {
        return totalRefundedAmountMinor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getVersion() {
        return version;
    }
}
