package com.paymentflow.notification.repository;

import com.paymentflow.notification.domain.WebhookDelivery;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    /**
     * V1's lookup, still used by the legacy retry listener draining pre-M18.6 rows.
     * Remains an {@code Optional} safely: fan-out rows leave {@code event_id} null, so
     * this can still match at most the one legacy row per event.
     */
    Optional<WebhookDelivery> findByEventId(UUID eventId);

    /** The retry sweeper's batch (M18.7): pending deliveries whose scheduled attempt has come due. */
    @Query("""
            select delivery from WebhookDelivery delivery
            where delivery.status = com.paymentflow.notification.domain.DeliveryStatus.PENDING
              and delivery.nextAttemptAt is not null
              and delivery.nextAttemptAt <= :now
            order by delivery.nextAttemptAt asc
            """)
    List<WebhookDelivery> findDueForRetry(@Param("now") Instant now, Limit limit);

    /** The delivery-log query (M18.8) — merchant- and mode-scoped, newest first. */
    Page<WebhookDelivery> findByMerchantIdAndModeOrderByCreatedAtDesc(UUID merchantId, String mode, Pageable pageable);

    /** Every delivery of one canonical event, for the replay API and the event's own log view. */
    List<WebhookDelivery> findByWebhookEventIdOrderByCreatedAtAsc(UUID webhookEventId);

    Optional<WebhookDelivery> findByIdAndMerchantIdAndMode(UUID id, UUID merchantId, String mode);
}
