package com.paymentflow.notification.repository;

import com.paymentflow.notification.domain.WebhookDeliveryAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Attempts are reachable only through a {@link com.paymentflow.notification.domain.WebhookDelivery},
 * which M18.6 makes endpoint-bound and therefore merchant- and mode-scoped — so, like
 * {@link WebhookSubscriptionRepository}, querying by the parent id alone is safe because
 * obtaining that id already required passing the boundary check.
 */
public interface WebhookDeliveryAttemptRepository extends JpaRepository<WebhookDeliveryAttempt, UUID> {

    /** The delivery log, in the order the attempts actually happened (M18.8). */
    List<WebhookDeliveryAttempt> findByDeliveryIdOrderByAttemptNumberAsc(UUID deliveryId);

    /** The log list view's batch read — one query for a page of deliveries rather than N. */
    List<WebhookDeliveryAttempt> findByDeliveryIdInOrderByAttemptNumberAsc(Collection<UUID> deliveryIds);

    /** The next attempt number for a delivery, and the retry schedule's own position counter (M18.7). */
    long countByDeliveryId(UUID deliveryId);
}
