package com.paymentflow.audit.service;

import com.paymentflow.audit.domain.AuditLogEntry;
import com.paymentflow.audit.repository.AuditLogEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private AuditLogEntryRepository auditLogEntryRepository;

    private AuditService auditService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        auditService = new AuditService(auditLogEntryRepository);
    }

    private JsonNode envelope(UUID eventId) {
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "PaymentAuthorized",
                  "aggregateId": "%s",
                  "occurredAt": "2026-07-18T10:15:30Z",
                  "correlationId": "corr-1",
                  "payload": {"paymentId": "%s", "status": "AUTHORIZED"}
                }
                """.formatted(eventId, UUID.randomUUID(), UUID.randomUUID());
        return objectMapper.readTree(json);
    }

    @Test
    void newEventIsRecordedWithThePayloadPreservedVerbatim() {
        UUID eventId = UUID.randomUUID();
        when(auditLogEntryRepository.existsByEventId(eventId)).thenReturn(false);

        auditService.recordEvent(envelope(eventId));

        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogEntryRepository).save(captor.capture());
        AuditLogEntry saved = captor.getValue();
        assertThat(saved.getEventId()).isEqualTo(eventId);
        assertThat(saved.getEventType()).isEqualTo("PaymentAuthorized");
        assertThat(saved.getCorrelationId()).isEqualTo("corr-1");
        assertThat(objectMapper.readTree(saved.getPayload()).get("status").asString()).isEqualTo("AUTHORIZED");
    }

    @Test
    void alreadyRecordedEventIsSkipped() {
        UUID eventId = UUID.randomUUID();
        when(auditLogEntryRepository.existsByEventId(eventId)).thenReturn(true);

        auditService.recordEvent(envelope(eventId));

        verify(auditLogEntryRepository, never()).save(any());
    }

    @Test
    void eventWithNullCorrelationIdIsRecordedWithoutOne() {
        UUID eventId = UUID.randomUUID();
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "PaymentCreated",
                  "aggregateId": "%s",
                  "occurredAt": "2026-07-18T10:15:30Z",
                  "correlationId": null,
                  "payload": {"paymentId": "%s"}
                }
                """.formatted(eventId, UUID.randomUUID(), UUID.randomUUID());
        when(auditLogEntryRepository.existsByEventId(eventId)).thenReturn(false);

        auditService.recordEvent(objectMapper.readTree(json));

        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogEntryRepository).save(captor.capture());
        assertThat(captor.getValue().getCorrelationId()).isNull();
    }

    @Test
    void aConcurrentDuplicateInsertIsSwallowedNotPropagated() {
        UUID eventId = UUID.randomUUID();
        when(auditLogEntryRepository.existsByEventId(eventId)).thenReturn(false);
        when(auditLogEntryRepository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatCode(() -> auditService.recordEvent(envelope(eventId))).doesNotThrowAnyException();
    }
}
