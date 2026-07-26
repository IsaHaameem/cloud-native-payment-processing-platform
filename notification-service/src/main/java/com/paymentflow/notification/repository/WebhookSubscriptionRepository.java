package com.paymentflow.notification.repository;

import com.paymentflow.notification.domain.WebhookSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Subscriptions carry no merchant or mode of their own — they are reachable only
 * through a {@link com.paymentflow.notification.domain.WebhookEndpoint} that is already
 * scoped by both, so every caller here must resolve the endpoint through
 * {@link WebhookEndpointRepository}'s scoped lookups first. Querying by
 * {@code endpointId} alone is safe precisely because obtaining that id already required
 * passing the boundary check.
 */
public interface WebhookSubscriptionRepository extends JpaRepository<WebhookSubscription, UUID> {

    List<WebhookSubscription> findByEndpointId(UUID endpointId);

    /**
     * The list view's and fan-out's batch read — one query for N endpoints rather than
     * the N+1 a per-endpoint lookup inside a loop would produce.
     */
    List<WebhookSubscription> findByEndpointIdIn(Collection<UUID> endpointIds);

    boolean existsByEndpointIdAndEventType(UUID endpointId, String eventType);

    /**
     * Replacing an endpoint's subscription set is a delete-then-insert rather than a
     * diff: the set is small, the operation is rare, and a wholesale replacement has no
     * partial-application failure mode to reason about.
     */
    long deleteByEndpointId(UUID endpointId);
}
