package com.paymentflow.notification.service;

import com.paymentflow.notification.domain.WebhookDelivery;
import com.paymentflow.notification.domain.WebhookEndpoint;
import com.paymentflow.notification.domain.WebhookEvent;
import com.paymentflow.notification.domain.WebhookSubscription;
import com.paymentflow.notification.repository.WebhookDeliveryRepository;
import com.paymentflow.notification.repository.WebhookEndpointRepository;
import com.paymentflow.notification.repository.WebhookSubscriptionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Turns one canonical event into N deliveries — one per <em>enabled</em> endpoint whose
 * subscriptions select that event type (M18.6, §4.5). This replaces V1's single-URL path:
 * where V1 read {@code merchantWebhookUrl} off the payment event and produced exactly one
 * delivery, fan-out reads the endpoint table and produces as many as are subscribed,
 * including none.
 *
 * <p>Endpoint selection is deliberately mode-scoped at the repository layer
 * ({@code findByMerchantIdAndModeAndEnabledTrue}), so a test-mode event can never reach a
 * live endpoint — the isolation guarantee M16 built, applied to the one subsystem that
 * sends data outside the platform.
 *
 * <p>Creates rows only; it makes no HTTP call and publishes nothing. Dispatch is the
 * caller's job after the transaction commits (D134), for the same reason V1 attempted its
 * first delivery post-commit: an external call has no place inside a database
 * transaction.
 */
@Service
public class WebhookFanOutService {

    private static final Logger log = LoggerFactory.getLogger(WebhookFanOutService.class);

    private final WebhookEndpointRepository endpointRepository;
    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final MeterRegistry meterRegistry;

    public WebhookFanOutService(WebhookEndpointRepository endpointRepository,
                                WebhookSubscriptionRepository subscriptionRepository,
                                WebhookDeliveryRepository deliveryRepository, MeterRegistry meterRegistry) {
        this.endpointRepository = endpointRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.deliveryRepository = deliveryRepository;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Creates a {@code PENDING} delivery per subscribed endpoint. Returns the created
     * deliveries so the caller can dispatch them once its transaction commits.
     *
     * <p>Must run inside the caller's transaction: the deliveries and the
     * {@code processed_events} marker have to commit together, or a crash between them
     * would leave an event that is never delivered and never retried.
     */
    public List<WebhookDelivery> fanOut(WebhookEvent event) {
        List<WebhookEndpoint> endpoints =
                endpointRepository.findByMerchantIdAndModeAndEnabledTrue(event.getMerchantId(), event.getMode());
        if (endpoints.isEmpty()) {
            return List.of();
        }

        Map<UUID, List<WebhookSubscription>> subscriptions =
                subscriptionRepository.findByEndpointIdIn(endpoints.stream().map(WebhookEndpoint::getId).toList())
                        .stream()
                        .collect(Collectors.groupingBy(WebhookSubscription::getEndpointId));

        List<WebhookDelivery> created = new ArrayList<>();
        for (WebhookEndpoint endpoint : endpoints) {
            boolean subscribed = subscriptions.getOrDefault(endpoint.getId(), List.of()).stream()
                    .anyMatch(subscription -> subscription.matches(event.getEventType()));
            if (!subscribed) {
                continue;
            }
            created.add(deliveryRepository.save(
                    WebhookDelivery.forEndpoint(event.getId(), endpoint.getId(), event.getMerchantId(),
                            event.getMode(), endpoint.getUrl())));
        }

        if (!created.isEmpty()) {
            meterRegistry.counter("webhook_fanout_deliveries_total", "eventType", event.getEventType())
                    .increment(created.size());
        }
        log.debug("Event {} ({}) fanned out to {} of {} enabled endpoint(s)",
                event.getEventRef(), event.getEventType(), created.size(), endpoints.size());
        return created;
    }

    /** The set of endpoint ids a given event type would reach — used by the replay API's validation (M18.8). */
    public Set<UUID> subscribedEndpointIds(UUID merchantId, String mode, String eventType) {
        List<WebhookEndpoint> endpoints = endpointRepository.findByMerchantIdAndModeAndEnabledTrue(merchantId, mode);
        Map<UUID, List<WebhookSubscription>> subscriptions =
                subscriptionRepository.findByEndpointIdIn(endpoints.stream().map(WebhookEndpoint::getId).toList())
                        .stream()
                        .collect(Collectors.groupingBy(WebhookSubscription::getEndpointId));
        return endpoints.stream()
                .map(WebhookEndpoint::getId)
                .filter(id -> subscriptions.getOrDefault(id, List.of()).stream()
                        .anyMatch(subscription -> subscription.matches(eventType)))
                .collect(Collectors.toUnmodifiableSet());
    }
}
