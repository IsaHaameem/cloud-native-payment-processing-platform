package com.paymentflow.notification.domain;

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
import java.util.Locale;
import java.util.UUID;

/**
 * The canonical, merchant-facing event object (M18.1, §4.5) — distinct from the
 * internal Kafka {@code EventEnvelope}, which carries this platform's own vocabulary
 * and is not a public promise. This row is what M19's Events API serves *and* what is
 * serialized into the webhook body, so "what the dashboard shows" and "what the
 * endpoint received" are the same object by construction rather than by two code paths
 * agreeing with each other.
 *
 * <p>{@link #eventRef} is the public {@code evt_...} identifier, derived
 * deterministically from the source envelope's {@code eventId} rather than randomly
 * generated. That determinism is deliberate and load-bearing for M19: audit-service
 * stores the same envelope {@code eventId} and must project its own rows into this
 * exact shape, and a derived id lets it do so with no shared sequence, no coordination,
 * and no lookup back into the {@code notification} schema.
 *
 * <p>{@code mode} is non-null and resolved at write time — an envelope carrying no mode
 * is read as {@code "live"}, which is D125's stated consumer semantics. This differs
 * from {@code EmailLogEntry}'s and {@link WebhookDelivery}'s nullable, never-coerced
 * mode (D126) because this table is queried *by* mode: a null would be unqueryable
 * rather than merely unknown.
 */
@Entity
@Table(name = "webhook_events")
public class WebhookEvent {

    /** The public identifier's prefix, matching the platform's {@code pk_}/{@code sk_}/{@code whsec_} convention. */
    public static final String ID_PREFIX = "evt_";

    private static final String LIVE_MODE = "live";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_ref", nullable = false, updatable = false, unique = true, length = 40)
    private String eventRef;

    @Column(name = "source_event_id", nullable = false, updatable = false, unique = true)
    private UUID sourceEventId;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(nullable = false, updatable = false, length = 4)
    private String mode;

    @Column(name = "event_type", nullable = false, updatable = false, length = 64)
    private String eventType;

    @Column(name = "api_version", nullable = false, updatable = false, length = 20)
    private String apiVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private String data;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "correlation_id", updatable = false, length = 64)
    private String correlationId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WebhookEvent() {
        // Required by JPA.
    }

    private WebhookEvent(String eventRef, UUID sourceEventId, UUID merchantId, String mode, String eventType,
                         String apiVersion, String data, Instant occurredAt, String correlationId) {
        this.eventRef = eventRef;
        this.sourceEventId = sourceEventId;
        this.merchantId = merchantId;
        this.mode = mode;
        this.eventType = eventType;
        this.apiVersion = apiVersion;
        this.data = data;
        this.occurredAt = occurredAt;
        this.correlationId = correlationId;
    }

    public static WebhookEvent of(UUID sourceEventId, UUID merchantId, String declaredMode, String eventType,
                                  String apiVersion, String data, Instant occurredAt, String correlationId) {
        return new WebhookEvent(eventRefFor(sourceEventId), sourceEventId, merchantId, resolveMode(declaredMode),
                eventType, apiVersion, data, occurredAt, correlationId);
    }

    /**
     * The deterministic public id for an internal event id: {@code "evt_"} followed by
     * the UUID's 32 hex digits, dashes removed. Any service holding the same envelope
     * {@code eventId} derives the identical value without consulting this schema — the
     * property M19's Events API depends on.
     */
    public static String eventRefFor(UUID sourceEventId) {
        return ID_PREFIX + sourceEventId.toString().replace("-", "").toLowerCase(Locale.ROOT);
    }

    /**
     * D125: a consumer reading a {@code null} envelope mode treats it as {@code "live"}.
     * Public because the canonical body must show the same resolved value this row
     * stores — a factory that resolved it separately could drift, and the body and the
     * row would then disagree about which partition an event belongs to.
     */
    public static String resolveMode(String declaredMode) {
        return (declaredMode == null || declaredMode.isBlank()) ? LIVE_MODE : declaredMode;
    }

    public UUID getId() {
        return id;
    }

    public String getEventRef() {
        return eventRef;
    }

    public UUID getSourceEventId() {
        return sourceEventId;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getMode() {
        return mode;
    }

    public String getEventType() {
        return eventType;
    }

    public String getApiVersion() {
        return apiVersion;
    }

    public String getData() {
        return data;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
