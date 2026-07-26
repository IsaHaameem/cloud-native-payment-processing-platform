package com.paymentflow.notification.service;

import com.paymentflow.notification.domain.WebhookDelivery;
import com.paymentflow.notification.repository.WebhookDeliveryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.domain.Limit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Publishes deliveries whose scheduled retry has come due (M18.7) onto
 * {@code webhook.deliveries.retry}, and dead-letters the ones that have exhausted the
 * published schedule.
 *
 * <p><b>Why a polling relay rather than a delayed Kafka message.</b> The schedule spans
 * ~24 hours, and Kafka has no native per-message delay: the alternatives are parking a
 * consumer thread on a `sleep` (V1's D46 approach, which is defensible for a 30-second
 * backoff and untenable for a six-hour one — it would hold a partition assignment for
 * hours and stall every other delivery on it), or a tier of delay topics per interval,
 * which multiplies topics by schedule steps. Polling a `next_attempt_at` column is the
 * same shape payment-service's `OutboxRelay` (D3) and sandbox-service's
 * `ScheduledOutcomeRelay` (M17.6) already use in this platform, and it survives a restart
 * for free — a sleeping consumer thread does not.
 *
 * <p>Also the reason `next_attempt_at` is a column rather than only a Kafka delay: a
 * delivery's next attempt has to be visible in the delivery log, answerable by the API,
 * and durable across a deploy.
 */
@Component
public class WebhookRetryRelay {

    private static final Logger log = LoggerFactory.getLogger(WebhookRetryRelay.class);
    private static final int BATCH_SIZE = 100;

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookDispatcher dispatcher;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    public WebhookRetryRelay(WebhookDeliveryRepository deliveryRepository, WebhookDispatcher dispatcher,
                             KafkaTemplate<String, String> kafkaTemplate, MeterRegistry meterRegistry) {
        this.deliveryRepository = deliveryRepository;
        this.dispatcher = dispatcher;
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${paymentflow.webhooks.retry-relay-interval-ms:1000}")
    @Transactional
    public void relay() {
        List<WebhookDelivery> due = deliveryRepository.findDueForRetry(Instant.now(), Limit.of(BATCH_SIZE));
        for (WebhookDelivery delivery : due) {
            // Cleared before publishing so a redelivery of the relay's own tick cannot
            // publish the same attempt twice; the delivery listener's idempotency check
            // is the second line of defence rather than the only one.
            delivery.scheduleNextAttemptAt(null);
            deliveryRepository.save(delivery);
            dispatcher.dispatch(delivery.getId(), delivery.getEndpointId());
            meterRegistry.counter("webhook_delivery_retries_dispatched_total").increment();
        }
        if (!due.isEmpty()) {
            log.debug("Dispatched {} due webhook retries", due.size());
        }
    }

    /**
     * Announces a dead-lettered delivery on {@code webhook.deliveries.dlq}. The row is
     * already {@code DEAD_LETTERED} by the time this runs — the topic is an operational
     * signal (and a hook for a future alert), not the record itself, which is the same
     * division V1's D46 DLQ used.
     */
    public void deadLetter(WebhookDelivery delivery) {
        kafkaTemplate.send(WebhookDispatcher.TOPIC + ".dlq", delivery.getId().toString());
        meterRegistry.counter("webhook_delivery_attempts_total", "outcome", "dead_lettered").increment();
        log.warn("Webhook delivery {} to endpoint {} dead-lettered after {} attempts",
                delivery.getId(), delivery.getEndpointId(), delivery.getAttemptCount());
    }
}
