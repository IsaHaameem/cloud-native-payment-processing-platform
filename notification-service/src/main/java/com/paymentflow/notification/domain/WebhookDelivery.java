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

    @Column(name = "event_id", nullable = false, updatable = false, unique = true)
    private UUID eventId;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    // The test/live partition the source event declared (M16), recorded verbatim.
    // Nullable for consistency with email_log and legacy rows, though in practice always
    // set: deliveries are created only from (mode-bearing) payment events. Audit-style
    // recorder semantics — never coerced to live (D126). Mode-scoped webhook endpoints
    // are M18's concern (§4.5), which rebuilds this subsystem.
    @Column(updatable = false, length = 4)
    private String mode;

    @Column(name = "webhook_url", nullable = false, updatable = false, length = 2048)
    private String webhookUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "jsonb")
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

    public void markDelivered() {
        this.status = DeliveryStatus.DELIVERED;
        this.lastAttemptedAt = Instant.now();
    }

    public void recordFailedAttempt() {
        this.attemptCount++;
        this.lastAttemptedAt = Instant.now();
    }

    public void markDeadLettered() {
        this.status = DeliveryStatus.DEAD_LETTERED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
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
