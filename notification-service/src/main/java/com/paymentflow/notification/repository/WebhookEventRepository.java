package com.paymentflow.notification.repository;

import com.paymentflow.notification.domain.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {

    /**
     * The fan-out dedup gate (M18.3/M18.6): one internal event yields exactly one
     * canonical event however many times Kafka redelivers it (D2). Not merchant-scoped —
     * {@code sourceEventId} is the platform's own internal id, never client-supplied, and
     * this is the idempotency check rather than a merchant-facing read.
     */
    Optional<WebhookEvent> findBySourceEventId(UUID sourceEventId);

    boolean existsBySourceEventId(UUID sourceEventId);

    /**
     * The merchant-facing read by public {@code evt_} id — scoped by merchant and mode
     * (D101/D102), unlike {@link #findBySourceEventId}, because this one <em>is</em>
     * reachable with a client-supplied identifier.
     */
    Optional<WebhookEvent> findByEventRefAndMerchantIdAndMode(String eventRef, UUID merchantId, String mode);
}
