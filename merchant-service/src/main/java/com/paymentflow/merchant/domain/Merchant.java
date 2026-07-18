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
