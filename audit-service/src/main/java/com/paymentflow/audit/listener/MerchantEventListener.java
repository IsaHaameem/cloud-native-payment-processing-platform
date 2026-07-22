package com.paymentflow.audit.listener;

import com.paymentflow.audit.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code merchant.events} (M15, task 12) — merchant onboarding and API-key
 * lifecycle. Reuses {@link AuditService#recordEvent} unchanged: audit's job is to
 * record whatever event came through verbatim regardless of topic (D44), so a second
 * producer needs no new service-layer code, only a second listener declaring which
 * topic and consumer group it reads from (D5's naming convention).
 */
@Component
public class MerchantEventListener {

    private static final Logger log = LoggerFactory.getLogger(MerchantEventListener.class);

    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public MerchantEventListener(AuditService auditService, ObjectMapper objectMapper) {
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${paymentflow.kafka.merchant-events-topic}",
            groupId = "${paymentflow.kafka.merchant-events-group-id}",
            concurrency = "${paymentflow.kafka.listener-concurrency}")
    public void onMessage(String json) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(json);
        } catch (Exception e) {
            log.error("Could not parse event, dropping: {}", json, e);
            return;
        }

        auditService.recordEvent(envelope);
    }
}
