package com.paymentflow.merchant.event;

import com.paymentflow.common.correlation.CorrelationConstants;
import com.paymentflow.common.dto.event.EventEnvelope;
import com.paymentflow.merchant.domain.ApiKey;
import com.paymentflow.merchant.domain.Merchant;
import com.paymentflow.merchant.domain.OutboxEvent;
import com.paymentflow.merchant.repository.OutboxEventRepository;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * Writes an outbox row for a merchant/key lifecycle event (M15, task 12) — mirrors
 * {@code PaymentEventPublisher} exactly (D3): callers invoke this from within the same
 * transaction as the mutation it describes; this class only ever appends to
 * {@code outbox_events}, it never talks to Kafka directly.
 */
@Component
public class MerchantEventPublisher {

    public static final String TOPIC = "merchant.events";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public MerchantEventPublisher(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    public void publishMerchantOnboarded(Merchant merchant) {
        publish(merchant.getId(), "MerchantOnboarded", new MerchantEventPayload(
                merchant.getId(), merchant.getBusinessName(), merchant.getContactEmail(),
                null, null, null, null, null));
    }

    public void publishApiKeyEvent(String eventType, ApiKey key) {
        publish(key.getMerchantId(), eventType, new MerchantEventPayload(
                key.getMerchantId(), null, null,
                key.getId(), key.getKeyPrefix(), key.getKeyType().name(), key.getMode().name(), key.getScopes()));
    }

    private void publish(UUID aggregateId, String eventType, MerchantEventPayload payload) {
        String correlationId = MDC.get(CorrelationConstants.CORRELATION_ID_MDC_KEY);
        EventEnvelope<MerchantEventPayload> envelope =
                EventEnvelope.of(eventType, aggregateId.toString(), correlationId, payload);
        String json = objectMapper.writeValueAsString(envelope);
        outboxEventRepository.save(OutboxEvent.of(aggregateId, eventType, TOPIC, json));
    }
}
