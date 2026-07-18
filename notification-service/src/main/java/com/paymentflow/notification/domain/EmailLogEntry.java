package com.paymentflow.notification.domain;

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
 * A simulated email send — no real SMTP/SES integration exists in this milestone (D45);
 * this row is the durable, queryable record of what would have been sent, always written
 * (every merchant has a {@code contactEmail}) regardless of whether a webhook is also
 * configured.
 */
@Entity
@Table(name = "email_log")
public class EmailLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false, unique = true)
    private UUID eventId;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(name = "recipient_email", nullable = false, updatable = false)
    private String recipientEmail;

    @Column(name = "subject", nullable = false, updatable = false)
    private String subject;

    @Column(name = "body", nullable = false, updatable = false)
    private String body;

    @CreationTimestamp
    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    protected EmailLogEntry() {
        // Required by JPA.
    }

    private EmailLogEntry(UUID eventId, UUID merchantId, String recipientEmail, String subject, String body) {
        this.eventId = eventId;
        this.merchantId = merchantId;
        this.recipientEmail = recipientEmail;
        this.subject = subject;
        this.body = body;
    }

    public static EmailLogEntry of(UUID eventId, UUID merchantId, String recipientEmail, String subject, String body) {
        return new EmailLogEntry(eventId, merchantId, recipientEmail, subject, body);
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getRecipientEmail() {
        return recipientEmail;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
