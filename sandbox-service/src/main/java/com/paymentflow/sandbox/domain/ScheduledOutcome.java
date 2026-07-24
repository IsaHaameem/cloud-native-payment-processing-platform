package com.paymentflow.sandbox.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A deferred outcome scheduled to fire later (M17.6, §4.2) — sandbox-service's own
 * transactional-outbox row, written in the same transaction as the decision that
 * scheduled it ({@code SandboxDecisionService}). {@link ScheduledOutcomeRelay} polls
 * for {@code deliveredAt IS NULL AND fireAt <= now()} and publishes to
 * {@code sandbox.scheduled.events}, mirroring payment-service's {@code OutboxEvent}
 * (D3) exactly.
 */
@Entity
@Table(name = "scheduled_outcomes")
public class ScheduledOutcome {

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private Operation operation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 24)
    private DecisionOutcome outcome;

    @Column(name = "fire_at", nullable = false, updatable = false)
    private Instant fireAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ScheduledOutcome() {
        // Required by JPA.
    }

    private ScheduledOutcome(UUID paymentId, UUID merchantId, String mode, Operation operation,
                             DecisionOutcome outcome, Instant fireAt) {
        this.paymentId = paymentId;
        this.merchantId = merchantId;
        this.mode = mode;
        this.operation = operation;
        this.outcome = outcome;
        this.fireAt = fireAt;
    }

    public static ScheduledOutcome create(UUID paymentId, UUID merchantId, String mode, Operation operation,
                                          DecisionOutcome outcome, Instant fireAt) {
        return new ScheduledOutcome(paymentId, merchantId, mode, operation, outcome, fireAt);
    }

    public void markDelivered() {
        this.deliveredAt = Instant.now();
    }

    public boolean isDelivered() {
        return deliveredAt != null;
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

    public Operation getOperation() {
        return operation;
    }

    public DecisionOutcome getOutcome() {
        return outcome;
    }

    public Instant getFireAt() {
        return fireAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
