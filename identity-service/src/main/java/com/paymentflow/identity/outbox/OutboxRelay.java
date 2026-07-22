package com.paymentflow.identity.outbox;

import com.paymentflow.identity.domain.OutboxEvent;
import com.paymentflow.identity.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Polling outbox relay (D3), mirrored from payment-service's M5 {@code OutboxRelay}:
 * publishes unpublished {@code outbox_events} rows to Kafka and marks them published.
 * A row that fails to publish is left unpublished for the next tick to retry —
 * at-least-once (D2); consumers dedupe on the event's {@code eventId}.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    public OutboxRelay(OutboxEventRepository outboxEventRepository, KafkaTemplate<String, String> kafkaTemplate,
                       MeterRegistry meterRegistry) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${paymentflow.outbox.relay-interval-ms:2000}")
    @Transactional
    public void relay() {
        List<OutboxEvent> batch = outboxEventRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();
        for (OutboxEvent event : batch) {
            publishOne(event);
        }
    }

    private void publishOne(OutboxEvent event) {
        try {
            kafkaTemplate.send(event.getTopic(), event.getAggregateId().toString(), event.getPayload())
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            event.markPublished();
            meterRegistry.counter("outbox_relay_publish_total", "topic", event.getTopic(), "outcome", "success")
                    .increment();
        } catch (Exception e) {
            log.error("Failed to publish outbox event {} (type={}, topic={}) — will retry next tick",
                    event.getId(), event.getEventType(), event.getTopic(), e);
            meterRegistry.counter("outbox_relay_publish_total", "topic", event.getTopic(), "outcome", "failure")
                    .increment();
        }
    }
}
