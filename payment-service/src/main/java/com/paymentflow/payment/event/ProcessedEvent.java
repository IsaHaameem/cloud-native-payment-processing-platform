package com.paymentflow.payment.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Marks an inbound Kafka event as already handled (M17.6, D2: at-least-once delivery,
 * idempotent consumer) — payment-service's first Kafka consumer role. Mirrors
 * transaction-service's own {@code ProcessedEvent} exactly, as payment-service's own
 * table (schema-per-service, D4): the unique constraint on {@code eventId} is what
 * actually enforces dedup, this row existing is the durable proof a redelivery should
 * no-op. Generic by design — not scoped to any one topic or producer.
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false, unique = true)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 50)
    private String eventType;

    @CreationTimestamp
    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
        // Required by JPA.
    }

    private ProcessedEvent(UUID eventId, String eventType) {
        this.eventId = eventId;
        this.eventType = eventType;
    }

    public static ProcessedEvent of(UUID eventId, String eventType) {
        return new ProcessedEvent(eventId, eventType);
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
