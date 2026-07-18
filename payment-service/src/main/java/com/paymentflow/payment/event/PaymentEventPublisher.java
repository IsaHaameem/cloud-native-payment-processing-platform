package com.paymentflow.payment.event;

import com.paymentflow.common.correlation.CorrelationConstants;
import com.paymentflow.common.dto.event.EventEnvelope;
import com.paymentflow.payment.domain.OutboxEvent;
import com.paymentflow.payment.domain.Payment;
import com.paymentflow.payment.domain.PaymentStatus;
import com.paymentflow.payment.merchant.MerchantSummary;
import com.paymentflow.payment.repository.OutboxEventRepository;
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

    public PaymentEventPublisher(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    public void publish(Payment payment, String eventType, PaymentStatus previousStatus, long eventAmountMinor,
                        MerchantSummary merchant) {
        PaymentEventPayload payload = new PaymentEventPayload(
                payment.getId(), payment.getMerchantId(), payment.getAmountMinor(), payment.getCurrency(),
                payment.getStatus().name(), previousStatus == null ? null : previousStatus.name(), eventAmountMinor,
                merchant.contactEmail(), merchant.webhookUrl());

        String correlationId = MDC.get(CorrelationConstants.CORRELATION_ID_MDC_KEY);
        EventEnvelope<PaymentEventPayload> envelope =
                EventEnvelope.of(eventType, payment.getId().toString(), correlationId, payload);

        String json = objectMapper.writeValueAsString(envelope);
        outboxEventRepository.save(OutboxEvent.of(payment.getId(), eventType, TOPIC, json));
    }
}
