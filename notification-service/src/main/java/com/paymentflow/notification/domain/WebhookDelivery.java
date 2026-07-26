package com.paymentflow.notification.domain;

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
 * Tracks one webhook delivery sequence for a single event, from the first (synchronous,
 * post-commit) attempt through however many retries {@code payment.events.retry}
 * carries it, to an eventual {@code DELIVERED} or {@code DEAD_LETTERED} (D46). Only
 * created when the merchant has a {@code webhookUrl} configured — absence of a row for
 * an event means there was nothing to deliver, not a failure.
 */
@Entity
@Table(name = "webhook_deliveries")
public class WebhookDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    // V1's key: the internal Kafka event id, one row per event. Nullable from M18.6 —
    // fan-out rows are keyed by (webhookEventId, endpointId) instead. Retained, not
    // dropped: existing rows are a real delivery history (§13-Q9's reasoning).
    @Column(name = "event_id", updatable = false)
    private UUID eventId;

    // M18.6: the canonical event and the endpoint this row delivers it to. One event
    // produces as many of these as are subscribed.
    @Column(name = "webhook_event_id", updatable = false)
    private UUID webhookEventId;

    @Column(name = "endpoint_id", updatable = false)
    private UUID endpointId;

    // When the next retry is due (M18.7's explicit schedule). Null means no further
    // attempt is scheduled — either resolved, or awaiting its immediate first dispatch.
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    // A replay (M18.8) is a new delivery pointing back at the one it re-sends, so the
    // original's history stays exactly what happened the first time.
    @Column(name = "replayed_from_delivery_id", updatable = false)
    private UUID replayedFromDeliveryId;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    // The test/live partition the source event declared (M16), recorded verbatim.
    // Nullable for consistency with email_log and legacy rows, though in practice always
    // set: deliveries are created only from (mode-bearing) payment events. Audit-style
    // recorder semantics — never coerced to live (D126). Mode-scoped webhook endpoints
    // are M18's concern (§4.5), which rebuilds this subsystem.
    @Column(updatable = false, length = 4)
    private String mode;

    @Column(name = "webhook_url", updatable = false, length = 2048)
    private String webhookUrl;

    // V1 stored the internal envelope here. From M18.6 the body is rendered from the
    // canonical event at send time (so a retry re-signs with a fresh timestamp), leaving
    // this null on fan-out rows.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", updatable = false, columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DeliveryStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_attempted_at")
    private Instant lastAttemptedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected WebhookDelivery() {
        // Required by JPA.
    }

    private WebhookDelivery(UUID eventId, UUID merchantId, String mode, String webhookUrl, String payload) {
        this.eventId = eventId;
        this.merchantId = merchantId;
        this.mode = mode;
        this.webhookUrl = webhookUrl;
        this.payload = payload;
        this.status = DeliveryStatus.PENDING;
        this.attemptCount = 0;
    }

    public static WebhookDelivery pending(UUID eventId, UUID merchantId, String mode, String webhookUrl, String payload) {
        return new WebhookDelivery(eventId, merchantId, mode, webhookUrl, payload);
    }

    /**
     * A fan-out delivery of one canonical event to one endpoint (M18.6). The URL is
     * snapshotted from the endpoint at creation so the delivery log records where the
     * attempt was actually sent, even if the endpoint is later deleted; the body is
     * <em>not</em> snapshotted, because every attempt re-renders and re-signs it with a
     * fresh timestamp.
     */
    public static WebhookDelivery forEndpoint(UUID webhookEventId, UUID endpointId, UUID merchantId, String mode,
                                              String webhookUrl) {
        WebhookDelivery delivery = new WebhookDelivery(null, merchantId, mode, webhookUrl, null);
        delivery.webhookEventId = webhookEventId;
        delivery.endpointId = endpointId;
        return delivery;
    }

    /**
     * A replay of an existing delivery (M18.8) — a new row with its own attempts,
     * pointing back at the original, which is left exactly as it was.
     */
    public static WebhookDelivery replayOf(WebhookDelivery original, String webhookUrl) {
        WebhookDelivery replay = new WebhookDelivery(null, original.merchantId, original.mode, webhookUrl, null);
        replay.webhookEventId = original.webhookEventId;
        replay.endpointId = original.endpointId;
        replay.replayedFromDeliveryId = original.id;
        return replay;
    }

    /** Schedules the next attempt, or clears the schedule when {@code at} is null. */
    public void scheduleNextAttemptAt(Instant at) {
        this.nextAttemptAt = at;
    }

    public void markDelivered() {
        this.status = DeliveryStatus.DELIVERED;
        this.lastAttemptedAt = Instant.now();
        this.nextAttemptAt = null;
    }

    public void recordFailedAttempt() {
        this.attemptCount++;
        this.lastAttemptedAt = Instant.now();
    }

    public void markDeadLettered() {
        this.status = DeliveryStatus.DEAD_LETTERED;
        this.nextAttemptAt = null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getWebhookEventId() {
        return webhookEventId;
    }

    public UUID getEndpointId() {
        return endpointId;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public UUID getReplayedFromDeliveryId() {
        return replayedFromDeliveryId;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getMode() {
        return mode;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public String getPayload() {
        return payload;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getLastAttemptedAt() {
        return lastAttemptedAt;
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
