package com.paymentflow.sandbox.scheduler;

import com.paymentflow.common.dto.event.EventEnvelope;
import com.paymentflow.sandbox.domain.ScheduledOutcome;
import com.paymentflow.sandbox.event.SandboxScheduledOutcomePayload;
import com.paymentflow.sandbox.repository.ScheduledOutcomeRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Polling outbox relay for deferred outcomes (M17.6, §4.2) — mirrors payment-service's
 * {@code OutboxRelay} (D3) exactly, just gated additionally by {@code fireAt}: publishes
 * due, undelivered {@code scheduled_outcomes} rows to {@code sandbox.scheduled.events}
 * and marks them delivered. A row that fails to publish is left undelivered for the
 * next tick — at-least-once (D2); payment-service's consumer dedupes on the event's
 * {@code eventId}, the same contract every other consumer in this platform already
 * relies on.
 */
@Component
public class ScheduledOutcomeRelay {

    public static final String TOPIC = "sandbox.scheduled.events";
    public static final String EVENT_TYPE = "DeferredOutcomeSettled";

    private static final Logger log = LoggerFactory.getLogger(ScheduledOutcomeRelay.class);
    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final ScheduledOutcomeRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public ScheduledOutcomeRelay(ScheduledOutcomeRepository repository, KafkaTemplate<String, String> kafkaTemplate,
                                 ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${paymentflow.scheduled-outcomes.relay-interval-ms:1000}")
    @Transactional
    public void relay() {
        List<ScheduledOutcome> due = repository.findTop50ByDeliveredAtIsNullAndFireAtLessThanEqualOrderByFireAtAsc(
                Instant.now());
        for (ScheduledOutcome outcome : due) {
            publishOne(outcome);
        }
    }

    private void publishOne(ScheduledOutcome outcome) {
        try {
            SandboxScheduledOutcomePayload payload = new SandboxScheduledOutcomePayload(
                    outcome.getPaymentId(), outcome.getOperation().name(), outcome.getOutcome().name());
            EventEnvelope<SandboxScheduledOutcomePayload> envelope = EventEnvelope.of(
                    EVENT_TYPE, outcome.getPaymentId().toString(), null, outcome.getMode(), payload);
            String json = objectMapper.writeValueAsString(envelope);

            kafkaTemplate.send(TOPIC, outcome.getPaymentId().toString(), json).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            outcome.markDelivered();
            meterRegistry.counter("scheduled_outcome_relay_publish_total", "outcome", "success").increment();
        } catch (Exception e) {
            log.error("Failed to publish scheduled outcome {} (payment={}, operation={}) — will retry next tick",
                    outcome.getId(), outcome.getPaymentId(), outcome.getOperation(), e);
            meterRegistry.counter("scheduled_outcome_relay_publish_total", "outcome", "failure").increment();
        }
    }
}
