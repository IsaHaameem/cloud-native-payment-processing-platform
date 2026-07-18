package com.paymentflow.transaction.listener;

import com.paymentflow.common.dto.event.EventEnvelope;
import com.paymentflow.transaction.event.PaymentLedgerEventPayload;
import com.paymentflow.transaction.service.LedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code payment.events} (D5's convention: consumer group
 * {@code <service>-<topic>}). Deserializes manually with the app's own Jackson 3
 * {@code ObjectMapper} rather than configuring a Kafka {@code JsonDeserializer} —
 * same reasoning as payment-service's producer side (M5): avoids any risk of
 * spring-kafka's own (de)serializer classes assuming Jackson 2.
 */
@Component
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    private final LedgerService ledgerService;
    private final ObjectMapper objectMapper;

    public PaymentEventListener(LedgerService ledgerService, ObjectMapper objectMapper) {
        this.ledgerService = ledgerService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${paymentflow.kafka.payment-events-topic}",
            concurrency = "${paymentflow.kafka.listener-concurrency}")
    public void onMessage(String json) {
        EventEnvelope<PaymentLedgerEventPayload> envelope;
        try {
            envelope = objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructParametricType(EventEnvelope.class, PaymentLedgerEventPayload.class));
        } catch (Exception e) {
            // Malformed message — not retryable by reprocessing. Retry/DLQ topics for
            // consumers are deferred to M7 (same YAGNI call as D-outbox-retry-topics
            // in M5); logging and dropping is the honest behavior without that
            // infrastructure, rather than pretending to handle it.
            log.error("Could not parse payment event, dropping: {}", json, e);
            return;
        }

        ledgerService.processEvent(envelope);
    }
}
