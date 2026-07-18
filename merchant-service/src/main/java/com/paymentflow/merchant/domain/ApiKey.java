package com.paymentflow.merchant.domain;

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
 * A merchant API key. The raw value is never stored — only its SHA-256 hash — so a
 * database leak cannot be used to authenticate as the merchant. {@code keyPrefix} is
 * the first few characters of the raw key, kept in the clear so a merchant can tell
 * their keys apart without re-exposing the secret. Rotation revokes the previous key
 * (kept for audit) and issues a fresh one; only one key is active at a time.
 */
@Entity
@Table(name = "api_keys")
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "key_prefix", nullable = false, updatable = false)
    private String keyPrefix;

    @Column(name = "key_hash", nullable = false, unique = true, updatable = false)
    private String keyHash;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ApiKey() {
        // Required by JPA.
    }

    private ApiKey(UUID merchantId, String keyPrefix, String keyHash) {
        this.merchantId = merchantId;
        this.keyPrefix = keyPrefix;
        this.keyHash = keyHash;
    }

    public static ApiKey issue(UUID merchantId, String keyPrefix, String keyHash) {
        return new ApiKey(merchantId, keyPrefix, keyHash);
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    public void revoke() {
        this.revokedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
