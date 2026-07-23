package com.paymentflow.sandbox.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A row of the test-card catalogue (§8.1) — reference data seeded via Flyway
 * (V2__seed_test_cards.sql), never merchant- or mode-scoped: the catalogue is
 * identical for every caller. {@code token} (e.g. {@code pm_card_visa}) is the natural
 * primary key; there is no synthetic id, since nothing ever references a row except by
 * its token.
 */
@Entity
@Table(name = "test_cards")
public class TestCard {

    @Id
    private String token;

    @Column(nullable = false, length = 20)
    private String brand;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private DecisionOutcome outcome;

    @Column(name = "decline_code", length = 48)
    private String declineCode;

    @Column(name = "error_code", length = 48)
    private String errorCode;

    @Column(name = "latency_ms", nullable = false)
    private int latencyMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "capture_behaviour", nullable = false, length = 16)
    private CaptureBehaviour captureBehaviour;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_behaviour", nullable = false, length = 16)
    private RefundBehaviour refundBehaviour;

    @Column(name = "deferred_delay_ms")
    private Integer deferredDelayMs;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private boolean active;

    protected TestCard() {
        // Required by JPA.
    }

    public String getToken() {
        return token;
    }

    public String getBrand() {
        return brand;
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

    public CaptureBehaviour getCaptureBehaviour() {
        return captureBehaviour;
    }

    public RefundBehaviour getRefundBehaviour() {
        return refundBehaviour;
    }

    public Integer getDeferredDelayMs() {
        return deferredDelayMs;
    }

    public String getDescription() {
        return description;
    }

    public boolean isActive() {
        return active;
    }
}
