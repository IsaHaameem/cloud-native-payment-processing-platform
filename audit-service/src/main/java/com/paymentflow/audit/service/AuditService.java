package com.paymentflow.audit.service;

import com.paymentflow.audit.domain.AuditLogEntry;
import com.paymentflow.audit.repository.AuditLogEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Records one immutable audit row per event, idempotently (D2). Unlike
 * transaction-service's ledger (M6), there is no shared mutable row for concurrent
 * events to contend on — each event writes its own independent row — so a genuine race
 * on the same {@code eventId} (e.g. two rebalanced consumer instances briefly both
 * holding the same partition) simply trips the unique constraint on insert; there is
 * nothing to retry, since the other side's insert already recorded the exact same
 * event. That is treated as a benign duplicate, not an error.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogEntryRepository auditLogEntryRepository;

    public AuditService(AuditLogEntryRepository auditLogEntryRepository) {
        this.auditLogEntryRepository = auditLogEntryRepository;
    }

    @Transactional
    public void recordEvent(JsonNode envelope) {
        UUID eventId = UUID.fromString(envelope.get("eventId").asString());
        if (auditLogEntryRepository.existsByEventId(eventId)) {
            log.debug("Event {} already recorded, skipping", eventId);
            return;
        }

        String eventType = envelope.get("eventType").asString();
        String aggregateId = envelope.get("aggregateId").asString();
        Instant occurredAt = Instant.parse(envelope.get("occurredAt").asString());
        JsonNode correlationIdNode = envelope.get("correlationId");
        String correlationId = (correlationIdNode == null || correlationIdNode.isNull())
                ? null : correlationIdNode.asString();
        String payload = envelope.get("payload").toString();

        try {
            auditLogEntryRepository.save(
                    AuditLogEntry.of(eventId, eventType, aggregateId, occurredAt, correlationId, payload));
        } catch (DataIntegrityViolationException e) {
            log.debug("Event {} was recorded by a concurrent redelivery, ignoring", eventId);
        }
    }
}
