package com.paymentflow.payment.repository;

import com.paymentflow.payment.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    List<OutboxEvent> findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();

    /**
     * The most recent event published for a payment (M17.6) — every event already
     * carries the merchant's contact email/webhook URL (D43), so a system-triggered
     * mutation with no caller (a deferred-capture Kafka consumer) can source the same
     * fields from here instead of a fresh merchant-service call it has no request
     * context to make.
     */
    Optional<OutboxEvent> findTopByAggregateIdOrderByCreatedAtDesc(UUID aggregateId);
}
