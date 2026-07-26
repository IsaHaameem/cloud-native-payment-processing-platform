package com.paymentflow.payment.domain;

import com.paymentflow.payment.exception.IllegalPaymentStateTransitionException;
import com.paymentflow.payment.exception.InvalidRefundAmountException;
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
 * The payment aggregate. All lifecycle mutation goes through the methods below, each
 * of which enforces {@link PaymentStatus}'s transition table — there is no public
 * setter for {@code status}, so an illegal transition is a compile-time impossibility
 * to bypass, not just a runtime check.
 */
@Entity
@Table(name = "payments")
public class Payment {

    /** Metadata is never null on the wire or at rest — an absent object, not an absent value. */
    private static final String EMPTY_METADATA = "{}";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    // The test/live partition this payment lives in (M16, §4.4). Immutable: a payment is
    // created in exactly one mode and never migrates. Stored as the canonical lowercase
    // string ("test"/"live") — the same value carried on every event this payment emits.
    @Column(nullable = false, updatable = false, length = 4)
    private String mode;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentStatus status;

    @Column(name = "captured_amount_minor", nullable = false)
    private long capturedAmountMinor;

    @Column(name = "refunded_amount_minor", nullable = false)
    private long refundedAmountMinor;

    @Column(length = 500)
    private String description;

    // The test-card token this payment authorizes against (M17, D130). Nullable, with no
    // backfill — a payment created without one (every payment before M17, and every one
    // that still doesn't supply it) resolves to the mode default at authorize time
    // (M17.4): auto-approve in test, the simulated acquirer in live.
    @Column(name = "payment_method_token", updatable = false, length = 64)
    private String paymentMethodToken;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    // Free-form merchant-supplied key/value data (M19, §4.6), stored as jsonb and
    // filterable via containment against a GIN index. Never interpreted by this service:
    // it exists so a merchant can correlate a payment with their own order id without
    // this platform inventing a field for every integration.
    //
    // Mutable, unlike most of this aggregate: metadata is annotation, not state, so
    // correcting it must not require a new payment. It carries no lifecycle meaning and
    // no event is published for a metadata change.
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

    protected Payment() {
        // Required by JPA.
    }

    private Payment(UUID merchantId, String mode, long amountMinor, String currency, String description,
                   String paymentMethodToken, String metadata) {
        this.merchantId = merchantId;
        this.mode = mode;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.description = description;
        this.paymentMethodToken = paymentMethodToken;
        this.metadata = (metadata == null || metadata.isBlank()) ? EMPTY_METADATA : metadata;
        this.status = PaymentStatus.CREATED;
        this.capturedAmountMinor = 0;
        this.refundedAmountMinor = 0;
    }

    public static Payment create(UUID merchantId, String mode, long amountMinor, String currency, String description,
                                 String paymentMethodToken, String metadata) {
        return new Payment(merchantId, mode, amountMinor, currency, description, paymentMethodToken, metadata);
    }

    /** Replaces the metadata wholesale. Annotation, not state — no event, no FSM effect. */
    public void updateMetadata(String metadata) {
        this.metadata = (metadata == null || metadata.isBlank()) ? EMPTY_METADATA : metadata;
    }

    public void authorize() {
        transitionTo(PaymentStatus.AUTHORIZED);
    }

    /** Captures the full authorized amount — no partial capture in the approved lifecycle. */
    public void capture() {
        transitionTo(PaymentStatus.CAPTURED);
        this.capturedAmountMinor = this.amountMinor;
    }

    public void voidPayment() {
        transitionTo(PaymentStatus.VOIDED);
    }

    public void fail(String reason) {
        transitionTo(PaymentStatus.FAILED);
        this.failureReason = reason;
    }

    /**
     * Refunds part or all of the captured amount. Lands on {@link PaymentStatus#REFUNDED}
     * once the cumulative refunded amount reaches the captured amount, otherwise
     * {@link PaymentStatus#PARTIALLY_REFUNDED}.
     */
    public void refund(long refundAmountMinor) {
        if (status != PaymentStatus.CAPTURED && status != PaymentStatus.PARTIALLY_REFUNDED) {
            throw new IllegalPaymentStateTransitionException(status, PaymentStatus.REFUNDED);
        }
        long remaining = capturedAmountMinor - refundedAmountMinor;
        if (refundAmountMinor <= 0 || refundAmountMinor > remaining) {
            throw new InvalidRefundAmountException(refundAmountMinor, remaining);
        }
        long newRefundedTotal = refundedAmountMinor + refundAmountMinor;
        this.status = (newRefundedTotal == capturedAmountMinor) ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
        this.refundedAmountMinor = newRefundedTotal;
    }

    private void transitionTo(PaymentStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalPaymentStateTransitionException(status, target);
        }
        this.status = target;
    }

    public UUID getId() {
        return id;
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

    public PaymentStatus getStatus() {
        return status;
    }

    public long getCapturedAmountMinor() {
        return capturedAmountMinor;
    }

    public long getRefundedAmountMinor() {
        return refundedAmountMinor;
    }

    public String getDescription() {
        return description;
    }

    public String getPaymentMethodToken() {
        return paymentMethodToken;
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
