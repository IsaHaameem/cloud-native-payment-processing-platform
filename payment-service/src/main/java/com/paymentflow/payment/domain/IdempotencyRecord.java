package com.paymentflow.payment.domain;

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
 * A durable record of a completed idempotent request (§5): a replayed request bearing
 * the same {@code Idempotency-Key} returns {@link #responseBody} instead of
 * reprocessing. Only successful completions are recorded — a failed attempt does not
 * poison the key, so a legitimate retry after a transient error can still succeed.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    // The mode this idempotency key belongs to (M16): the same Idempotency-Key is a
    // distinct key per (merchant, mode), so a test retry never replays a live response.
    @Column(nullable = false, updatable = false, length = 4)
    private String mode;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, updatable = false)
    private String requestFingerprint;

    @Column(name = "response_status", nullable = false, updatable = false)
    private int responseStatus;

    @Column(name = "response_body", nullable = false, updatable = false)
    private String responseBody;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IdempotencyRecord() {
        // Required by JPA.
    }

    private IdempotencyRecord(UUID merchantId, String mode, String idempotencyKey, String requestFingerprint,
                              int responseStatus, String responseBody) {
        this.merchantId = merchantId;
        this.mode = mode;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
    }

    public static IdempotencyRecord of(UUID merchantId, String mode, String idempotencyKey, String requestFingerprint,
                                       int responseStatus, String responseBody) {
        return new IdempotencyRecord(merchantId, mode, idempotencyKey, requestFingerprint, responseStatus, responseBody);
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

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public int getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
