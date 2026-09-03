package com.paymentflow.agentic.provider;

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
 * A persisted provider decision (G-6).
 *
 * <p>Written by {@code ProviderDecisionController} after it computes a {@link ProviderDecision}
 * for payment-service. It is the durable, merchant-inspectable record of what an acquirer said
 * about one authorization attempt — and, crucially, of whether that "approval" was a real
 * cardholder authorization ({@code demo = false}) or a demonstration stand-in ({@code demo =
 * true}, {@code source = order_accepted}).
 *
 * <p>Provider-neutral by construction: no order id, no Razorpay error codes, nothing that would
 * teach a reader which acquirer this was beyond {@code providerName}.
 */
@Entity
@Table(name = "provider_decisions")
public class ProviderDecisionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(nullable = false, updatable = false, length = 4)
    private String mode;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "decision_key", nullable = false, updatable = false, length = 128)
    private String decisionKey;

    @Column(nullable = false, updatable = false, length = 32)
    private String operation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private ProviderOutcome outcome;

    @Column(name = "decline_code", updatable = false, length = 64)
    private String declineCode;

    @Column(name = "error_code", updatable = false, length = 64)
    private String errorCode;

    @Column(nullable = false, updatable = false, length = 32)
    private String source;

    @Column(name = "provider_reference", updatable = false, length = 128)
    private String providerReference;

    @Column(nullable = false, updatable = false)
    private boolean demo;

    @Column(name = "provider_name", nullable = false, updatable = false, length = 32)
    private String providerName;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "correlation_id", updatable = false, length = 64)
    private String correlationId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ProviderDecisionRecord() {
        // Required by JPA.
    }

    private ProviderDecisionRecord(UUID merchantId, String mode, UUID paymentId, String decisionKey,
                                   String operation, ProviderDecision decision, String providerName,
                                   long amountMinor, String currency, String correlationId) {
        this.merchantId = merchantId;
        this.mode = mode;
        this.paymentId = paymentId;
        this.decisionKey = decisionKey;
        this.operation = operation;
        this.outcome = decision.outcome();
        this.declineCode = decision.declineCode();
        this.errorCode = decision.errorCode();
        this.source = decision.source();
        this.providerReference = decision.providerReference();
        this.demo = decision.demo();
        this.providerName = providerName;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.correlationId = correlationId;
    }

    public static ProviderDecisionRecord of(UUID merchantId, String mode, UUID paymentId,
                                            String decisionKey, String operation, ProviderDecision decision,
                                            String providerName, long amountMinor, String currency,
                                            String correlationId) {
        return new ProviderDecisionRecord(merchantId, mode, paymentId, decisionKey, operation, decision,
                providerName, amountMinor, currency, correlationId);
    }

    /** Whether this record is a demonstration approval rather than a real cardholder authorization. */
    public boolean isDemoApproval() {
        return demo && outcome == ProviderOutcome.APPROVE;
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

    public UUID getPaymentId() {
        return paymentId;
    }

    public String getDecisionKey() {
        return decisionKey;
    }

    public String getOperation() {
        return operation;
    }

    public ProviderOutcome getOutcome() {
        return outcome;
    }

    public String getDeclineCode() {
        return declineCode;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getSource() {
        return source;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public boolean isDemo() {
        return demo;
    }

    public String getProviderName() {
        return providerName;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
