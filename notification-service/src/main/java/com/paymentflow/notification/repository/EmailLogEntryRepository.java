package com.paymentflow.notification.repository;

import com.paymentflow.notification.domain.EmailLogEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EmailLogEntryRepository extends JpaRepository<EmailLogEntry, UUID> {
}
