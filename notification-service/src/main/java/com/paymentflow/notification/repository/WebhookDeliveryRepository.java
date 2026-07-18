package com.paymentflow.notification.repository;

import com.paymentflow.notification.domain.WebhookDelivery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    Optional<WebhookDelivery> findByEventId(UUID eventId);
}
