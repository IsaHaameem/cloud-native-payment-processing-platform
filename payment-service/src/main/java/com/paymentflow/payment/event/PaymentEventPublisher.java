package com.paymentflow.payment.event;

import com.paymentflow.common.correlation.CorrelationConstants;
import com.paymentflow.common.dto.event.EventEnvelope;
import com.paymentflow.payment.domain.OutboxEvent;
import com.paymentflow.payment.domain.Payment;
import com.paymentflow.payment.domain.PaymentStatus;
import com.paymentflow.payment.merchant.MerchantSummary;
import com.paymentflow.payment.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes an outbox row for a payment lifecycle transition. Callers invoke this from
 * within the same transaction as the {@link Payment} mutation it describes (D3) —
 * this class only ever appends to {@code outbox_events}, it never talks to Kafka.
 */
@Component
public class PaymentEventPublisher {

    public static final String TOPIC = "payment.events";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public PaymentEventPublisher(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper,
                                 MeterRegistry meterRegistry) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    public void publish(Payment payment, String eventType, PaymentStatus previousStatus, long eventAmountMinor,
                        MerchantSummary merchant) {
        PaymentEventPayload payload = new PaymentEventPayload(
                payment.getId(), payment.getMerchantId(), payment.getAmountMinor(), payment.getCurrency(),
                payment.getStatus().name(), previousStatus == null ? null : previousStatus.name(), eventAmountMinor,
                merchant.contactEmail(), merchant.webhookUrl());

        String correlationId = MDC.get(CorrelationConstants.CORRELATION_ID_MDC_KEY);
        // Mode (M16) rides the envelope, not the payload — every consumer receives the
        // test/live partition without a lookup and cannot accidentally cross-post.
        EventEnvelope<PaymentEventPayload> envelope =
                EventEnvelope.of(eventType, payment.getId().toString(), correlationId, payment.getMode(), payload);

        String json = objectMapper.writeValueAsString(envelope);
        outboxEventRepository.save(OutboxEvent.of(payment.getId(), eventType, TOPIC, json));

        // Business metric (M13): every lifecycle transition, by event type and currency —
        // the payment funnel (created -> authorized -> captured -> refunded, with
        // voided/failed as off-ramps) a dashboard actually wants to chart. Recorded here,
        // the one place every mutation path (PaymentService.create/mutate) already funnels
        // through, rather than duplicated across each of PaymentService's 5 public methods.
        Counter.builder("payment_lifecycle_events_total")
                .description("Payment lifecycle transitions, by event type and currency")
                .tag("eventType", eventType)
                .tag("currency", payment.getCurrency())
                .register(meterRegistry)
                .increment();
    }
}
