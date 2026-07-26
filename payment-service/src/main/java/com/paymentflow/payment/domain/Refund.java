package com.paymentflow.payment.domain;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * A refund, as an object rather than an increment (M19.3). Before this it existed only
 * as a number added to {@code payments.refunded_amount_minor}, which can answer "how
 * much" and nothing else.
 *
 * <p>Deliberately <b>not</b> a second state machine. The payment's FSM already decides
 * whether a refund is legal and whether the payment lands on {@code PARTIALLY_REFUNDED}
 * or {@code REFUNDED}; this row records what happened, in the same transaction. A refund
 * is created already {@code SUCCEEDED} — the platform has no asynchronous refund
 * settlement, so a {@code PENDING} state would be a lie about a decision already made.
 * {@code FAILED} exists because a future acquirer-backed refund can fail, and the
 * enum having one value would be the shape that has to change when it does.
 *
 * <p>Carries {@code merchantId} and {@code mode} denormalized from its payment so every
 * query is scoped by a predicate on this table rather than a join a caller could forget
 * (D101).
 */
@Entity
@Table(name = "refunds")
public class Refund {

    private static final String EMPTY_METADATA = "{}";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(nullable = false, updatable = false, length = 4)
    private String mode;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RefundStatus status;

    @Column(length = 500)
    private String reason;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    private String metadata;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Refund() {
        // Required by JPA.
    }

    private Refund(UUID paymentId, UUID merchantId, String mode, long amountMinor, String currency,
                   RefundStatus status, String reason, String failureReason, String metadata) {
        this.paymentId = paymentId;
        this.merchantId = merchantId;
        this.mode = mode;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.status = status;
        this.reason = reason;
        this.failureReason = failureReason;
        this.metadata = (metadata == null || metadata.isBlank()) ? EMPTY_METADATA : metadata;
    }

    /** The only path today: the payment FSM already accepted the refund by the time this is called. */
    public static Refund succeeded(Payment payment, long amountMinor, String reason, String metadata) {
        return new Refund(payment.getId(), payment.getMerchantId(), payment.getMode(), amountMinor,
                payment.getCurrency(), RefundStatus.SUCCEEDED, reason, null, metadata);
    }

    /**
     * Reserved for an acquirer-backed refund that is rejected downstream. Unused today
     * and deliberately present: the schema's {@code chk_refunds_failure_shape} constraint
     * already models it, and adding the state later would be a migration rather than a
     * constructor.
     */
    public static Refund failed(Payment payment, long amountMinor, String reason, String failureReason,
                                String metadata) {
        return new Refund(payment.getId(), payment.getMerchantId(), payment.getMode(), amountMinor,
                payment.getCurrency(), RefundStatus.FAILED, reason, failureReason, metadata);
    }

    public void updateMetadata(String metadata) {
        this.metadata = (metadata == null || metadata.isBlank()) ? EMPTY_METADATA : metadata;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getMode() {
        return mode;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public RefundStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getMetadata() {
        return metadata;
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
