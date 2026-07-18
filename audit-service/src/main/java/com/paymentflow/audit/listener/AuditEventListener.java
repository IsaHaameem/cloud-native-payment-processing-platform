package com.paymentflow.audit.listener;

import com.paymentflow.audit.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code payment.events} (D5's convention: consumer group
 * {@code <service>-<topic>}). Parsed as a generic JSON tree, not a typed payload class —
 * audit-service's entire job is to record whatever event came through verbatim, so it has
 * no business reason to couple to any specific event's payload shape (D44).
 */
@Component
public class AuditEventListener {

    private static final Logger log = LoggerFactory.getLogger(AuditEventListener.class);

    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public AuditEventListener(AuditService auditService, ObjectMapper objectMapper) {
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${paymentflow.kafka.payment-events-topic}",
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
