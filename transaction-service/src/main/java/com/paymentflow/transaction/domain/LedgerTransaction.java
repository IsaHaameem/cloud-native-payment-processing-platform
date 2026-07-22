package com.paymentflow.transaction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** A journal entry: the balanced set of debit/credit {@link LedgerEntry} legs posted for one payment lifecycle event. */
@Entity
@Table(name = "ledger_transactions")
public class LedgerTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 50)
    private String eventType;

    // The test/live partition this journal entry belongs to (M16) — denormalized here so
    // the M19 ledger API can filter by mode without joining through to the accounts.
    @Column(nullable = false, updatable = false, length = 4)
    private String mode;

    @Column(length = 500)
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerTransaction() {
        // Required by JPA.
    }

    private LedgerTransaction(UUID paymentId, UUID eventId, String eventType, String mode, String description) {
        this.paymentId = paymentId;
        this.eventId = eventId;
        this.eventType = eventType;
        this.mode = mode;
        this.description = description;
    }

    public static LedgerTransaction of(UUID paymentId, UUID eventId, String eventType, String mode, String description) {
        return new LedgerTransaction(paymentId, eventId, eventType, mode, description);
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getMode() {
        return mode;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
