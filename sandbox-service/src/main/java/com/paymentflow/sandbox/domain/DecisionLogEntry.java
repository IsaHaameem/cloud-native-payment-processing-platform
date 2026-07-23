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
 * One row per advisory decision (§4.2) — append-only, and the mechanism behind D128's
 * decision-key idempotency: {@code decisionKey} is unique, so a retried advice call
 * (payment-service's Resilience4j Retry, M17.4) finds its own prior row instead of
 * evaluating — and for M17.5+, consuming an override — a second time. No update method
 * exists; a decision, once recorded, is permanent.
 *
 * <p>{@code overrideId} has no foreign key until M17.5 introduces
 * {@code simulation_overrides} (there is nothing to reference yet); it stays {@code
 * null} for every M17.2–M17.4 decision, since no override lookup exists before M17.5.
 */
@Entity
@Table(name = "decision_log")
public class DecisionLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "decision_key", nullable = false, updatable = false, unique = true, length = 128)
    private String decisionKey;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(nullable = false, updatable = false, length = 4)
    private String mode;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private Operation operation;

    @Column(name = "payment_method_token", updatable = false, length = 64)
    private String paymentMethodToken;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 24)
    private DecisionOutcome outcome;

    @Column(name = "decline_code", updatable = false, length = 48)
    private String declineCode;

    @Column(name = "error_code", updatable = false, length = 48)
    private String errorCode;

    @Column(name = "latency_ms", nullable = false, updatable = false)
    private int latencyMs;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private DecisionSource source;

    @Column(name = "override_id", updatable = false)
    private UUID overrideId;

    @Enumerated(EnumType.STRING)
    @Column(name = "deferred_operation", updatable = false, length = 16)
    private Operation deferredOperation;

    @Column(name = "deferred_delay_ms", updatable = false)
    private Integer deferredDelayMs;

    @Column(name = "correlation_id", updatable = false, length = 64)
    private String correlationId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DecisionLogEntry() {
        // Required by JPA.
    }

    private DecisionLogEntry(String decisionKey, UUID merchantId, String mode, UUID paymentId, Operation operation,
                             String paymentMethodToken, long amountMinor, String currency, DecisionOutcome outcome,
                             String declineCode, String errorCode, int latencyMs, DecisionSource source,
                             UUID overrideId, Operation deferredOperation, Integer deferredDelayMs,
                             String correlationId) {
        this.decisionKey = decisionKey;
        this.merchantId = merchantId;
        this.mode = mode;
        this.paymentId = paymentId;
        this.operation = operation;
        this.paymentMethodToken = paymentMethodToken;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.outcome = outcome;
        this.declineCode = declineCode;
        this.errorCode = errorCode;
        this.latencyMs = latencyMs;
        this.source = source;
        this.overrideId = overrideId;
        this.deferredOperation = deferredOperation;
        this.deferredDelayMs = deferredDelayMs;
        this.correlationId = correlationId;
    }

    public static DecisionLogEntry of(String decisionKey, UUID merchantId, String mode, UUID paymentId,
                                      Operation operation, String paymentMethodToken, long amountMinor,
                                      String currency, DecisionOutcome outcome, String declineCode, String errorCode,
                                      int latencyMs, DecisionSource source, UUID overrideId,
                                      Operation deferredOperation, Integer deferredDelayMs, String correlationId) {
        return new DecisionLogEntry(decisionKey, merchantId, mode, paymentId, operation, paymentMethodToken,
                amountMinor, currency, outcome, declineCode, errorCode, latencyMs, source, overrideId,
                deferredOperation, deferredDelayMs, correlationId);
    }

    public UUID getId() {
        return id;
    }

    public String getDecisionKey() {
        return decisionKey;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getMode() {
        return mode;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public Operation getOperation() {
        return operation;
    }

    public String getPaymentMethodToken() {
        return paymentMethodToken;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public DecisionOutcome getOutcome() {
        return outcome;
    }

    public String getDeclineCode() {
        return declineCode;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public int getLatencyMs() {
        return latencyMs;
    }

    public DecisionSource getSource() {
        return source;
    }

    public UUID getOverrideId() {
        return overrideId;
    }

    public Operation getDeferredOperation() {
        return deferredOperation;
    }

    public Integer getDeferredDelayMs() {
        return deferredDelayMs;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
