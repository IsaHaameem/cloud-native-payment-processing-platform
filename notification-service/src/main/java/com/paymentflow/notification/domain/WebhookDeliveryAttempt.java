package com.paymentflow.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * One HTTP attempt at delivering a webhook (M18.1, §4.5) — the request actually sent
 * and the response actually received. This is the delivery log the dashboard renders
 * and the docs point a stuck integrator at, which is why it records the request
 * verbatim per attempt instead of referencing {@link WebhookEvent#getData()}: a retry
 * re-signs with a fresh timestamp and a replay may use a rotated secret, so the bytes
 * genuinely differ between attempts, and a shared reference would show a merchant
 * something they were never sent.
 *
 * <p>Its parent is the per-{@code (event, endpoint)} {@link WebhookDelivery} aggregate,
 * not the event: a replay creates a new delivery with its own attempts rather than
 * appending attempts to the original (M18.8), so the original's history stays exactly
 * what happened the first time.
 *
 * <p>Immutable once written. An attempt is a historical fact; nothing about it can
 * legitimately change afterwards, so there is no mutator and no {@code @Version} —
 * unlike {@link WebhookDelivery}, whose whole purpose is to accumulate state.
 */
@Entity
@Table(name = "webhook_delivery_attempts")
public class WebhookDeliveryAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "delivery_id", nullable = false, updatable = false)
    private UUID deliveryId;

    @Column(name = "attempt_number", nullable = false, updatable = false)
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 24)
    private AttemptOutcome outcome;

    @Column(name = "request_url", nullable = false, updatable = false, length = 2048)
    private String requestUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_headers", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String requestHeaders;

    @Column(name = "request_body", nullable = false, updatable = false)
    private String requestBody;

    @Column(name = "response_status", updatable = false)
    private Integer responseStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_headers", updatable = false, columnDefinition = "jsonb")
    private String responseHeaders;

    // Truncated to a configured cap before it ever reaches this field (M18.6) — a
    // hostile endpoint returning gigabytes must not be able to fill this table
    // through us.
    @Column(name = "response_body", updatable = false)
    private String responseBody;

    @Column(name = "duration_ms", updatable = false)
    private Integer durationMs;

    @Column(updatable = false, length = 512)
    private String error;

    @CreationTimestamp
    @Column(name = "attempted_at", nullable = false, updatable = false)
    private Instant attemptedAt;

    protected WebhookDeliveryAttempt() {
        // Required by JPA.
    }

    private WebhookDeliveryAttempt(UUID deliveryId, int attemptNumber, AttemptOutcome outcome, String requestUrl,
                                   String requestHeaders, String requestBody, Integer responseStatus,
                                   String responseHeaders, String responseBody, Integer durationMs, String error) {
        this.deliveryId = deliveryId;
        this.attemptNumber = attemptNumber;
        this.outcome = outcome;
        this.requestUrl = requestUrl;
        this.requestHeaders = requestHeaders;
        this.requestBody = requestBody;
        this.responseStatus = responseStatus;
        this.responseHeaders = responseHeaders;
        this.responseBody = responseBody;
        this.durationMs = durationMs;
        this.error = error;
    }

    /** The endpoint answered — {@code SUCCEEDED} for a 2xx, {@code FAILED_STATUS} otherwise. */
    public static WebhookDeliveryAttempt answered(UUID deliveryId, int attemptNumber, int responseStatus,
                                                  String requestUrl, String requestHeaders, String requestBody,
                                                  String responseHeaders, String responseBody, int durationMs) {
        AttemptOutcome outcome = (responseStatus >= 200 && responseStatus < 300)
                ? AttemptOutcome.SUCCEEDED
                : AttemptOutcome.FAILED_STATUS;
        return new WebhookDeliveryAttempt(deliveryId, attemptNumber, outcome, requestUrl, requestHeaders, requestBody,
                responseStatus, responseHeaders, responseBody, durationMs, null);
    }

    /** No response was ever received — connect failure, read timeout, or TLS error. */
    public static WebhookDeliveryAttempt transportFailed(UUID deliveryId, int attemptNumber, String requestUrl,
                                                          String requestHeaders, String requestBody, int durationMs,
                                                          String error) {
        return new WebhookDeliveryAttempt(deliveryId, attemptNumber, AttemptOutcome.FAILED_TRANSPORT, requestUrl,
                requestHeaders, requestBody, null, null, null, durationMs, error);
    }

    /**
     * The egress guard (M18.5) refused to make the call at all. Recorded as an attempt
     * rather than silently skipped: a merchant whose endpoint was never contacted needs
     * to be told exactly that, and the delivery log is where they will look.
     */
    public static WebhookDeliveryAttempt blocked(UUID deliveryId, int attemptNumber, String requestUrl,
                                                  String requestHeaders, String requestBody, String error) {
        return new WebhookDeliveryAttempt(deliveryId, attemptNumber, AttemptOutcome.BLOCKED, requestUrl,
                requestHeaders, requestBody, null, null, null, null, error);
    }

    public UUID getId() {
        return id;
    }

    public UUID getDeliveryId() {
        return deliveryId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public AttemptOutcome getOutcome() {
        return outcome;
    }

    public String getRequestUrl() {
        return requestUrl;
    }

    public String getRequestHeaders() {
        return requestHeaders;
    }

    public String getRequestBody() {
        return requestBody;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getResponseHeaders() {
        return responseHeaders;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public String getError() {
        return error;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }
}
