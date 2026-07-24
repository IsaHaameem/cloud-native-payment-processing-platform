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
 * A per-merchant, per-mode simulation override (§8.2, M17.5) — at most one active per
 * {@code (merchantId, mode)} (schema-enforced, see V4's partial unique index).
 * {@code remainingCount} and {@code revokedAt} are never mutated via entity setters:
 * both change only through {@code SimulationOverrideRepository}'s atomic
 * {@code @Modifying} queries (D127) — this class has no setters for either, so the
 * only way to change them is the mechanism the design actually requires.
 *
 * <p>No {@code @Version} — D127: {@code remainingCount} is a counter on a hot row under
 * concurrent authorizations, where optimistic locking would convert contention into
 * retry storms for no invariant that spans fields.
 */
@Entity
@Table(name = "simulation_overrides")
public class SimulationOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(nullable = false, updatable = false, length = 4)
    private String mode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 24)
    private SimulationScenario scenario;

    @Column(name = "decline_code", updatable = false, length = 48)
    private String declineCode;

    @Column(name = "error_code", updatable = false, length = 48)
    private String errorCode;

    @Column(name = "latency_ms", updatable = false)
    private Integer latencyMs;

    @Column(name = "remaining_count")
    private Integer remainingCount;

    @Column(name = "expires_at", updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SimulationOverride() {
        // Required by JPA.
    }

    private SimulationOverride(UUID merchantId, String mode, SimulationScenario scenario, String declineCode,
                               String errorCode, Integer latencyMs, Integer remainingCount, Instant expiresAt) {
        this.merchantId = merchantId;
        this.mode = mode;
        this.scenario = scenario;
        this.declineCode = declineCode;
        this.errorCode = errorCode;
        this.latencyMs = latencyMs;
        this.remainingCount = remainingCount;
        this.expiresAt = expiresAt;
    }

    public static SimulationOverride create(UUID merchantId, String mode, SimulationScenario scenario,
                                            String declineCode, String errorCode, Integer latencyMs,
                                            Integer remainingCount, Instant expiresAt) {
        return new SimulationOverride(
                merchantId, mode, scenario, declineCode, errorCode, latencyMs, remainingCount, expiresAt);
    }

    /** Not revoked, not past its expiry, and not exhausted by count — the three independent ways an override ends. */
    public boolean isActive(Instant now) {
        return revokedAt == null
                && (expiresAt == null || expiresAt.isAfter(now))
                && (remainingCount == null || remainingCount > 0);
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

    public SimulationScenario getScenario() {
        return scenario;
    }

    public String getDeclineCode() {
        return declineCode;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public Integer getRemainingCount() {
        return remainingCount;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
