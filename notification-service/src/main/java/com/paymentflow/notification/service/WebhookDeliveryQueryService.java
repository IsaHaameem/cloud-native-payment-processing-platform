package com.paymentflow.notification.service;

import com.paymentflow.common.exception.BadRequestException;
import com.paymentflow.common.exception.ResourceNotFoundException;
import com.paymentflow.notification.domain.WebhookDelivery;
import com.paymentflow.notification.domain.WebhookDeliveryAttempt;
import com.paymentflow.notification.domain.WebhookEndpoint;
import com.paymentflow.notification.repository.WebhookDeliveryAttemptRepository;
import com.paymentflow.notification.repository.WebhookDeliveryRepository;
import com.paymentflow.notification.repository.WebhookEndpointRepository;
import com.paymentflow.notification.repository.WebhookEventRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read access to the delivery log, and manual replay (M18.8, §4.5). Both are the
 * milestone's answer to "a developer who cannot see why a webhook did not arrive cannot
 * integrate" — the visibility half of the objective, where everything before it was the
 * delivery half.
 *
 * <p>Every read is scoped to the caller's verified merchant and mode, like every other
 * query in this platform; a delivery belonging to another merchant resolves to empty and
 * surfaces as 404, never 403 (D102).
 */
@Service
public class WebhookDeliveryQueryService {

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookDeliveryAttemptRepository attemptRepository;
    private final WebhookEndpointRepository endpointRepository;
    private final WebhookEventRepository eventRepository;
    private final WebhookDispatcher dispatcher;

    public WebhookDeliveryQueryService(WebhookDeliveryRepository deliveryRepository,
                                       WebhookDeliveryAttemptRepository attemptRepository,
                                       WebhookEndpointRepository endpointRepository,
                                       WebhookEventRepository eventRepository, WebhookDispatcher dispatcher) {
        this.deliveryRepository = deliveryRepository;
        this.attemptRepository = attemptRepository;
        this.endpointRepository = endpointRepository;
        this.eventRepository = eventRepository;
        this.dispatcher = dispatcher;
    }

    @Transactional(readOnly = true)
    public Page<WebhookDelivery> list(UUID merchantId, String mode, Pageable pageable) {
        return deliveryRepository.findByMerchantIdAndModeOrderByCreatedAtDesc(merchantId, mode, pageable);
    }

    @Transactional(readOnly = true)
    public WebhookDelivery get(UUID merchantId, String mode, UUID deliveryId) {
        return requireDelivery(merchantId, mode, deliveryId);
    }

    @Transactional(readOnly = true)
    public List<WebhookDeliveryAttempt> attemptsOf(UUID deliveryId) {
        return attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(deliveryId);
    }

    /** Attempts for many deliveries in one query — the list view's N+1 avoidance. */
    @Transactional(readOnly = true)
    public Map<UUID, List<WebhookDeliveryAttempt>> attemptsOf(List<UUID> deliveryIds) {
        if (deliveryIds.isEmpty()) {
            return Map.of();
        }
        return attemptRepository.findByDeliveryIdInOrderByAttemptNumberAsc(deliveryIds).stream()
                .collect(Collectors.groupingBy(WebhookDeliveryAttempt::getDeliveryId));
    }

    /**
     * Re-sends a past delivery as a <b>new</b> delivery with its own attempts (§4.5,
     * "replay works and is visible as a distinct attempt").
     *
     * <p>The original is never touched. That is the load-bearing property: a delivery log
     * that mutates when you replay it cannot answer "what happened the first time", which
     * is the question a merchant is usually asking when they reach for replay. The new row
     * points back through {@code replayed_from_delivery_id}, so the relationship is data
     * rather than something the reader has to infer from timestamps.
     *
     * <p>The event is re-rendered and re-signed at send time like any other attempt, so a
     * replay carries a current timestamp and passes the receiver's tolerance window — a
     * replay that reproduced the original signature would be rejected by any correctly
     * implemented receiver, which is precisely the check §9.4 tells them to perform.
     */
    // Deliberately not @Transactional: the dispatch below must happen after the row is
    // durable, never inside a transaction that could still roll back and leave a Kafka
    // message pointing at a delivery that does not exist — the same rule the fan-out path
    // follows (D134). The single save needs no explicit transaction of its own; the reads
    // above it are validations.
    public WebhookDelivery replay(UUID merchantId, String mode, UUID deliveryId) {
        WebhookDelivery original = requireDelivery(merchantId, mode, deliveryId);
        if (original.getWebhookEventId() == null || original.getEndpointId() == null) {
            // A pre-M18.6 row from V1's single-URL path: it has no canonical event and no
            // endpoint, so there is nothing coherent to re-send.
            throw new BadRequestException("This delivery predates the webhook subsystem and cannot be replayed.");
        }
        WebhookEndpoint endpoint = endpointRepository.findById(original.getEndpointId())
                .orElseThrow(() -> new BadRequestException("The endpoint for this delivery no longer exists."));
        if (!endpoint.isEnabled()) {
            // Replaying into a disabled endpoint would immediately be skipped by the
            // processor, leaving a delivery that never resolves. Refusing is the honest
            // answer, and it names the fix.
            throw new BadRequestException("This endpoint is disabled. Re-enable it before replaying.");
        }
        eventRepository.findById(original.getWebhookEventId())
                .orElseThrow(() -> new BadRequestException("The event for this delivery no longer exists."));

        WebhookDelivery replay = deliveryRepository.save(WebhookDelivery.replayOf(original, endpoint.getUrl()));
        dispatcher.dispatch(replay.getId(), replay.getEndpointId());
        return replay;
    }

    private WebhookDelivery requireDelivery(UUID merchantId, String mode, UUID deliveryId) {
        return deliveryRepository.findByIdAndMerchantIdAndMode(deliveryId, merchantId, mode)
                .orElseThrow(() -> ResourceNotFoundException.of("WebhookDelivery", deliveryId));
    }
}
