package com.paymentflow.merchant.domain;

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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A merchant API key (M15, D99 — supersedes V1's single-active-key model, D29). The
 * raw value is never stored — only its SHA-256 hash — so a database leak cannot be
 * used to authenticate as the merchant; {@code keyPrefix} is the first characters of
 * the raw key, kept in the clear so a merchant can tell their keys apart without
 * re-exposing the secret.
 *
 * <p>Many keys may be active for one merchant at once, independently scoped and
 * moded. Rotation does not revoke the previous key immediately — it grants it a grace
 * window ({@link #rotateWithGrace}) so an in-flight deploy using the old key doesn't
 * fail mid-rotation; grace expiry is a pure time check in {@link #isActive}, not a
 * scheduled job flipping a flag.
 */
@Entity
@Table(name = "api_keys")
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "key_type", nullable = false, updatable = false, length = 20)
    private ApiKeyType keyType;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, updatable = false, length = 10)
    private KeyMode mode;

    @Column(name = "name", length = 100)
    private String name;

    @Column(name = "key_prefix", nullable = false, updatable = false)
    private String keyPrefix;

    @Column(name = "key_hash", nullable = false, unique = true, updatable = false)
    private String keyHash;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "scopes", nullable = false, columnDefinition = "text[]")
    private List<String> scopes;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "grace_expires_at")
    private Instant graceExpiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ApiKey() {
        // Required by JPA.
    }

    private ApiKey(UUID merchantId, ApiKeyType keyType, KeyMode mode, String name, String keyPrefix, String keyHash,
                   List<String> scopes, Instant expiresAt) {
        this.merchantId = merchantId;
        this.keyType = keyType;
        this.mode = mode;
        this.name = name;
        this.keyPrefix = keyPrefix;
        this.keyHash = keyHash;
        this.scopes = List.copyOf(scopes);
        this.expiresAt = expiresAt;
    }

    public static ApiKey issue(UUID merchantId, ApiKeyType keyType, KeyMode mode, String name, String keyPrefix,
                               String keyHash, List<String> scopes, Instant expiresAt) {
        return new ApiKey(merchantId, keyType, mode, name, keyPrefix, keyHash, scopes, expiresAt);
    }

    /** True only right now — an expiry/grace window makes this a moving target, never cached across calls. */
    public boolean isActive(Instant now) {
        if (revokedAt != null) {
            return false;
        }
        if (expiresAt != null && !now.isBefore(expiresAt)) {
            return false;
        }
        return graceExpiresAt == null || now.isBefore(graceExpiresAt);
    }

    public boolean hasScope(String required) {
        return scopes.contains("*") || scopes.contains(required);
    }

    public void revoke() {
        this.revokedAt = Instant.now();
    }

    /**
     * Grants this (superseded) key a grace window instead of revoking it immediately —
     * it keeps authenticating until {@code graceDuration} elapses, then goes inactive
     * on its own via {@link #isActive}, with no scheduled job required.
     */
    public void rotateWithGrace(Duration graceDuration) {
        this.graceExpiresAt = Instant.now().plus(graceDuration);
    }

    public void touchLastUsed() {
        this.lastUsedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public ApiKeyType getKeyType() {
        return keyType;
    }

    public KeyMode getMode() {
        return mode;
    }

    public String getName() {
        return name;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public List<String> getScopes() {
        return scopes;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getGraceExpiresAt() {
        return graceExpiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
