package com.paymentflow.payment.outbox;

import com.paymentflow.payment.domain.OutboxEvent;
import com.paymentflow.payment.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Polling outbox relay (D3): publishes unpublished {@code outbox_events} rows to
 * Kafka and marks them published. A row that fails to publish is left unpublished for
 * the next tick to retry — at-least-once (D2); a would-be duplicate publish (e.g. the
 * broker ack arrives but this process crashes before marking the row published) is
 * accepted for the same reason: consumers must dedupe on the event's {@code eventId}.
 *
 * <p>No CDC/Debezium infrastructure exists in this platform (not in the tech stack) —
 * a simple poller is the standard outbox-relay implementation without one.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxRelay(OutboxEventRepository outboxEventRepository, KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
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
        } catch (Exception e) {
            log.error("Failed to publish outbox event {} (type={}, topic={}) — will retry next tick",
                    event.getId(), event.getEventType(), event.getTopic(), e);
        }
    }
}
