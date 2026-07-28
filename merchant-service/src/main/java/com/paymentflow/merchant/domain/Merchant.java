package com.paymentflow.merchant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/** A merchant business profile, owned by exactly one identity-service user account. */
@Entity
@Table(name = "merchants")
public class Merchant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_user_id", nullable = false, updatable = false, unique = true)
    private UUID ownerUserId;

    @Column(name = "business_name", nullable = false)
    private String businessName;

    @Column(name = "contact_email", nullable = false)
    private String contactEmail;

    @Column(name = "webhook_url")
    private String webhookUrl;

    /**
     * Per-merchant rate-limit and quota overrides (M20.5, D145). Null means "use the platform
     * default for this mode", so defaults live in the gateway's configuration and can change
     * for everyone without a data migration; a non-null value is an explicit decision someone
     * made about this merchant.
     */
    @Column(name = "rate_limit_per_second")
    private Integer rateLimitPerSecond;

    @Column(name = "rate_limit_burst")
    private Integer rateLimitBurst;

    @Column(name = "daily_quota")
    private Integer dailyQuota;

    /**
     * The revision of the public API contract this merchant is served (M21.5, §4.10).
     *
     * <p>Null means "has not called the public API yet" rather than "use the default" —
     * unlike the three overrides above, whose null is a steady state. The gateway writes
     * this on the first request it authenticates for this merchant, and it is never
     * rewritten afterwards: that is the whole promise of pinning, and an automatic move to a
     * newer revision would be exactly the coordinated upgrade date-based versioning exists
     * to avoid.
     */
    @Column(name = "pinned_api_version", length = 10)
    private String pinnedApiVersion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Merchant() {
        // Required by JPA.
    }

    private Merchant(UUID ownerUserId, String businessName, String contactEmail) {
        this.ownerUserId = ownerUserId;
        this.businessName = businessName;
        this.contactEmail = contactEmail;
    }

    public static Merchant onboard(UUID ownerUserId, String businessName, String contactEmail) {
        return new Merchant(ownerUserId, businessName, contactEmail);
    }

    public void updateProfile(String businessName, String contactEmail) {
        this.businessName = businessName;
        this.contactEmail = contactEmail;
    }

    /** {@code null} (or blank) clears the webhook — notification-service then skips delivery entirely. */
    public void updateWebhookUrl(String webhookUrl) {
        this.webhookUrl = (webhookUrl == null || webhookUrl.isBlank()) ? null : webhookUrl;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public String getBusinessName() {
        return businessName;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public Integer getRateLimitPerSecond() {
        return rateLimitPerSecond;
    }

    public Integer getRateLimitBurst() {
        return rateLimitBurst;
    }

    public Integer getDailyQuota() {
        return dailyQuota;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public String getPinnedApiVersion() {
        return pinnedApiVersion;
    }

    /**
     * Pins this merchant to a revision, if they are not pinned already (M21.5).
     *
     * <p>Returns whether anything changed, so the caller can skip a write on the
     * overwhelmingly common path — this is called on every authenticated request and does
     * something on exactly one of them per merchant, ever.
     *
     * <p>The "if not already pinned" guard lives here rather than in the service, because it
     * is the invariant: a pin that could be overwritten is not a pin. Making it structurally
     * impossible to move is cheaper than remembering not to.
     */
    public boolean pinApiVersionIfUnset(String version) {
        if (pinnedApiVersion != null) {
            return false;
        }
        pinnedApiVersion = version;
        return true;
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
