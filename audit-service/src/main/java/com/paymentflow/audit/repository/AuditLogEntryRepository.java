package com.paymentflow.audit.repository;

import com.paymentflow.audit.domain.AuditLogEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AuditLogEntryRepository extends JpaRepository<AuditLogEntry, UUID> {

    boolean existsByEventId(UUID eventId);
}
