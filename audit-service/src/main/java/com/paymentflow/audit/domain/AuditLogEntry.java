package com.paymentflow.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One immutable row per payment lifecycle event, stored verbatim (payload as an opaque
 * JSON tree, not a typed class — see the module javadoc/D44). The unique constraint on
 * {@code eventId} is what actually enforces idempotent redelivery (D2), mirroring
 * transaction-service's {@code ProcessedEvent} (M6) — audit-service just folds "was this
 * processed" and "the record itself" into a single table since there's nothing else to track.
 */
@Entity
@Table(name = "audit_log")
public class AuditLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false, unique = true)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 50)
    private String eventType;

    @Column(name = "aggregate_id", nullable = false, updatable = false, length = 100)
    private String aggregateId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "correlation_id", updatable = false, length = 100)
    private String correlationId;

    // The test/live partition the event declared (M16), recorded verbatim — nullable
    // because audit is a faithful, schema-agnostic recorder (D44) across multiple streams:
    // payment.events carry a mode, but merchant.events (key/merchant lifecycle) are
    // mode-less, and coercing those to 'live' would be a lie in an immutable trail (D126).
    @Column(updatable = false, length = 4)
    private String mode;

    // M19.5: extracted from the payload so the Events API can scope by merchant with an
    // indexed predicate rather than a jsonb dig. Nullable for the same reason mode is
    // (D126): audit consumes streams whose events genuinely belong to no merchant, and
    // inventing a value to satisfy a constraint would be a lie in an immutable trail.
    @Column(name = "merchant_id", updatable = false)
    private UUID merchantId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String payload;

    @CreationTimestamp
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    protected AuditLogEntry() {
        // Required by JPA.
    }

    private AuditLogEntry(UUID eventId, String eventType, String aggregateId, Instant occurredAt,
                          String correlationId, String mode, UUID merchantId, String payload) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.occurredAt = occurredAt;
        this.correlationId = correlationId;
        this.mode = mode;
        this.merchantId = merchantId;
        this.payload = payload;
    }

    public static AuditLogEntry of(UUID eventId, String eventType, String aggregateId, Instant occurredAt,
                                   String correlationId, String mode, UUID merchantId, String payload) {
        return new AuditLogEntry(eventId, eventType, aggregateId, occurredAt, correlationId, mode, merchantId,
                payload);
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

    public String getAggregateId() {
        return aggregateId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getMode() {
        return mode;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
