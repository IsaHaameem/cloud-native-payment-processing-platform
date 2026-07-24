package com.paymentflow.payment.authorization.sandbox;

import com.paymentflow.common.dto.event.EventEnvelope;
import com.paymentflow.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code sandbox.scheduled.events} (M17.6, D2's convention: consumer group
 * {@code <service>-<topic>}) — payment-service's first Kafka consumer role. A thin
 * translator only: parses the sandbox-shaped envelope and makes one plain,
 * sandbox-agnostic call on {@link PaymentService} — {@code operation}/{@code outcome}
 * strings and the payload type itself never cross out of this package (D132's
 * discipline extended to the Kafka boundary, not just the synchronous port).
 *
 * <p>Deserializes manually with the app's own Jackson 3 {@code ObjectMapper}, same
 * reasoning as every other consumer in this platform (transaction-service's
 * {@code PaymentEventListener}, M6): avoids any risk of spring-kafka's own
 * (de)serializer classes assuming Jackson 2.
 */
@Component
public class SandboxScheduledEventListener {

    private static final Logger log = LoggerFactory.getLogger(SandboxScheduledEventListener.class);
    private static final String OPERATION_CAPTURE = "CAPTURE";
    private static final String DEFAULT_MODE = "live";

    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    public SandboxScheduledEventListener(PaymentService paymentService, ObjectMapper objectMapper) {
        this.paymentService = paymentService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${paymentflow.kafka.sandbox-scheduled-events-topic}",
            concurrency = "${paymentflow.kafka.listener-concurrency}")
    public void onMessage(String json) {
        EventEnvelope<SandboxScheduledOutcomePayload> envelope;
        try {
            envelope = objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructParametricType(EventEnvelope.class, SandboxScheduledOutcomePayload.class));
        } catch (Exception e) {
            // Malformed message — not retryable by reprocessing, same "log and drop"
            // call every other consumer in this platform makes for the same reason
            // (M6/M7's YAGNI-on-retry-topics, D14/D31/D42/D61).
            log.error("Could not parse sandbox scheduled event, dropping: {}", json, e);
            return;
        }

        SandboxScheduledOutcomePayload payload = envelope.payload();
        String mode = envelope.mode() == null ? DEFAULT_MODE : envelope.mode();
        if (!OPERATION_CAPTURE.equals(payload.operation())) {
            // No other deferred operation is reachable through M17's own decomposition
            // (D131) — logged, not silently ignored, so a future scenario that does
            // schedule one doesn't disappear without a trace.
            log.warn("Sandbox scheduled event {} has an unhandled operation {} — no action taken",
                    envelope.eventId(), payload.operation());
            return;
        }

        paymentService.applyDeferredCapture(envelope.eventId(), envelope.eventType(), payload.paymentId(), mode);
    }
}
