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

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One registered webhook destination for a merchant, in one mode (M18.1, §4.5) —
 * replacing V1's single {@code merchants.webhook_url} as the source of truth for where
 * a webhook goes. Many may exist per merchant per mode, each independently subscribed
 * ({@link WebhookSubscription}), independently secreted, and independently
 * enable-able.
 *
 * <p>{@code mode} is non-null here, unlike {@link WebhookDelivery}'s and
 * {@code EmailLogEntry}'s nullable recorder-semantics mode (D126): this is a
 * partitioning column in M16.2–16.4's sense — a test endpoint receiving a live event
 * would be the exact isolation failure M16 exists to prevent — not a faithful
 * transcription of whatever an event happened to declare.
 *
 * <p>The signing secret is never stored in the clear — but, unlike {@code sk_} keys and
 * refresh tokens, it is <b>encrypted rather than hashed</b> (D137). Those are only ever
 * *verified*, so a one-way digest suffices; this one is *used* as an HMAC key on every
 * delivery, and a digest could only produce signatures the merchant could never
 * reproduce. Rotation grants the
 * superseded secret a bounded window rather than invalidating it instantly, so an
 * endpoint can roll without dropping in-flight deliveries — a pure time comparison at
 * read time, needing no scheduled job, exactly as {@code ApiKey.rotateWithGrace} does
 * for keys (D120).
 */
@Entity
@Table(name = "webhook_endpoints")
public class WebhookEndpoint {

    /** What {@code metadata} holds when a merchant supplies none — never null (M19.8). */
    private static final String EMPTY_METADATA = "{}";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(nullable = false, updatable = false, length = 4)
    private String mode;

    @Column(nullable = false, updatable = false, length = 2048)
    private String url;

    @Column(length = 255)
    private String description;

    @Column(nullable = false)
    private boolean enabled;

    // Pinned at registration so a merchant's integration keeps receiving the payload
    // shape it was written against once date-based versioning (D108) is real. One value
    // exists until M21; the column is populated now because backfilling a version pin
    // onto live endpoints later would be guesswork.
    @Column(name = "api_version", nullable = false, updatable = false, length = 20)
    private String apiVersion;

    // Encrypted, not hashed (D137): this secret is *used* as an HMAC key on every
    // delivery, so it must be recoverable — a one-way digest could only produce
    // signatures no merchant could reproduce. AES-256-GCM via WebhookSecretCipher.
    @Column(name = "signing_secret_encrypted", nullable = false, length = 255)
    private String signingSecretEncrypted;

    @Column(name = "signing_secret_prefix", nullable = false, length = 20)
    private String signingSecretPrefix;

    @Column(name = "previous_secret_encrypted", length = 255)
    private String previousSecretEncrypted;

    @Column(name = "previous_secret_expires_at")
    private Instant previousSecretExpiresAt;

    @Column(name = "consecutive_failure_count", nullable = false)
    private int consecutiveFailureCount;

    @Column(name = "disabled_at")
    private Instant disabledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "disabled_reason", length = 32)
    private EndpointDisableReason disabledReason;

    @Column(name = "migrated_from_legacy", nullable = false, updatable = false)
    private boolean migratedFromLegacy;

    // Where to write when the platform disables this endpoint (M18.7). Sourced from the
    // verified MerchantContext at registration (D118) or the payment event payload at
    // legacy adoption (D43) — notification-service never calls merchant-service to learn
    // it. Nullable: an endpoint without one simply gets no auto-disable email.
    @Column(name = "contact_email", length = 255)
    private String contactEmail;

    // Free-form merchant-supplied annotation (M19.8, §4.6). Mutable and outside the
    // factories deliberately: metadata carries no lifecycle meaning, participates in no
    // invariant, and defaults to an empty object — so unlike url/mode/secret it is not
    // part of what makes an endpoint well-formed, and a caller that never sets it still
    // gets a valid row. The same reasoning Payment.updateMetadata records.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    private String metadata = EMPTY_METADATA;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected WebhookEndpoint() {
        // Required by JPA.
    }

    private WebhookEndpoint(UUID merchantId, String mode, String url, String description, String apiVersion,
                            String signingSecretEncrypted, String signingSecretPrefix, boolean migratedFromLegacy,
                            String contactEmail) {
        this.merchantId = merchantId;
        this.mode = mode;
        this.url = url;
        this.description = description;
        this.apiVersion = apiVersion;
        this.signingSecretEncrypted = signingSecretEncrypted;
        this.signingSecretPrefix = signingSecretPrefix;
        this.migratedFromLegacy = migratedFromLegacy;
        this.contactEmail = contactEmail;
        this.enabled = true;
        this.consecutiveFailureCount = 0;
    }

    /** A merchant-registered endpoint, created through the management API (M18.2). */
    public static WebhookEndpoint register(UUID merchantId, String mode, String url, String description,
                                           String apiVersion, String signingSecretEncrypted,
                                           String signingSecretPrefix, String contactEmail) {
        return new WebhookEndpoint(merchantId, mode, url, description, apiVersion, signingSecretEncrypted,
                signingSecretPrefix, false, contactEmail);
    }

    /**
     * An endpoint adopted from V1's {@code merchants.webhook_url} (M18.9, D135) rather
     * than registered through the API. Flagged as data so the deprecation of that column
     * stays auditable instead of being inferred from a heuristic later.
     */
    public static WebhookEndpoint adoptLegacy(UUID merchantId, String mode, String url, String apiVersion,
                                              String signingSecretEncrypted, String signingSecretPrefix,
                                              String contactEmail) {
        return new WebhookEndpoint(merchantId, mode, url, "Migrated from the merchant's legacy webhook URL.",
                apiVersion, signingSecretEncrypted, signingSecretPrefix, true, contactEmail);
    }

    public void updateDescription(String description) {
        this.description = description;
    }

    /**
     * Replaces the metadata wholesale (M19.8). A wholesale replacement rather than a
     * merge, matching payments and refunds: a merchant clearing one key would otherwise
     * have no way to express it, since the map has no representation for "remove this".
     */
    public void updateMetadata(String metadata) {
        this.metadata = (metadata == null || metadata.isBlank()) ? EMPTY_METADATA : metadata;
    }

    /** Merchant-initiated disable — leaves {@code disabledAt}/{@code disabledReason} clear (see {@link EndpointDisableReason}). */
    public void disable() {
        this.enabled = false;
    }

    /** Re-enabling always clears the platform's own disable annotations and resets the failure streak. */
    public void enable() {
        this.enabled = true;
        this.disabledAt = null;
        this.disabledReason = null;
        this.consecutiveFailureCount = 0;
    }

    /** Platform-initiated disable (M18.7) — distinguishable from {@link #disable()} by the recorded reason. */
    public void autoDisable(EndpointDisableReason reason, Instant at) {
        this.enabled = false;
        this.disabledAt = at;
        this.disabledReason = reason;
    }

    /**
     * Rotates to a new secret, keeping the superseded one valid until {@code graceDuration}
     * elapses so an endpoint can roll without dropping deliveries (§4.5's dual-secret
     * window). Grace expiry is checked at read time by {@link #hasUsablePreviousSecret},
     * so no scheduled job is required and none can drift out of sync.
     */
    public void rotateSecret(String newSecretEncrypted, String newSecretPrefix, Duration graceDuration, Instant now) {
        this.previousSecretEncrypted = this.signingSecretEncrypted;
        this.previousSecretExpiresAt = now.plus(graceDuration);
        this.signingSecretEncrypted = newSecretEncrypted;
        this.signingSecretPrefix = newSecretPrefix;
    }

    /** True only right now — the grace window makes this a moving target, never cached across calls. */
    public boolean hasUsablePreviousSecret(Instant now) {
        return previousSecretEncrypted != null && previousSecretExpiresAt != null && now.isBefore(previousSecretExpiresAt);
    }

    /** A successful delivery ends the failure streak outright — auto-disable counts *consecutive* failures (§4.5). */
    public void recordDeliverySuccess() {
        this.consecutiveFailureCount = 0;
    }

    public void recordDeliveryFailure() {
        this.consecutiveFailureCount++;
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

    public String getUrl() {
        return url;
    }

    public String getDescription() {
        return description;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getApiVersion() {
        return apiVersion;
    }

    public String getSigningSecretEncrypted() {
        return signingSecretEncrypted;
    }

    public String getSigningSecretPrefix() {
        return signingSecretPrefix;
    }

    public String getPreviousSecretEncrypted() {
        return previousSecretEncrypted;
    }

    public Instant getPreviousSecretExpiresAt() {
        return previousSecretExpiresAt;
    }

    public int getConsecutiveFailureCount() {
        return consecutiveFailureCount;
    }

    public Instant getDisabledAt() {
        return disabledAt;
    }

    public EndpointDisableReason getDisabledReason() {
        return disabledReason;
    }

    public boolean isMigratedFromLegacy() {
        return migratedFromLegacy;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public String getMetadata() {
        return metadata;
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
