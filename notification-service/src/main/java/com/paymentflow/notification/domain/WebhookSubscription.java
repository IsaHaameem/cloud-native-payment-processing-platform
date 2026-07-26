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
 * One event type a {@link WebhookEndpoint} is subscribed to (M18.1, §4.5). Modelled as
 * its own row rather than a {@code text[]} column on the endpoint — unlike
 * {@code ApiKey.scopes}, which is exactly that — because fan-out (M18.6) queries in the
 * opposite direction ("which endpoints want this event type?"), which an array column
 * cannot index usefully and a join table can.
 *
 * <p>Deliberately holds no {@code @ManyToOne} back-reference to its endpoint, only the
 * foreign-key id: the platform's repositories never traverse object graphs across
 * aggregate boundaries, and a lazy association here would be one {@code open-in-view:
 * false} surprise waiting to happen inside the delivery path.
 *
 * <p>No {@code mode} column: a subscription is unreachable except through an endpoint
 * that is already mode-scoped, so carrying one here would be denormalisation that can
 * drift out of agreement with its parent.
 */
@Entity
@Table(name = "webhook_subscriptions")
public class WebhookSubscription {

    /** The wildcard subscription — matches every event type, mirroring the {@code "*"} scope convention. */
    public static final String ALL_EVENT_TYPES = "*";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "endpoint_id", nullable = false, updatable = false)
    private UUID endpointId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 64)
    private String eventType;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WebhookSubscription() {
        // Required by JPA.
    }

    private WebhookSubscription(UUID endpointId, String eventType) {
        this.endpointId = endpointId;
        this.eventType = eventType;
    }

    public static WebhookSubscription of(UUID endpointId, String eventType) {
        return new WebhookSubscription(endpointId, eventType);
    }

    /** Whether this subscription selects the given canonical event type. */
    public boolean matches(String candidateEventType) {
        return ALL_EVENT_TYPES.equals(eventType) || eventType.equals(candidateEventType);
    }

    public UUID getId() {
        return id;
    }

    public UUID getEndpointId() {
        return endpointId;
    }

    public String getEventType() {
        return eventType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
