package com.paymentflow.sandbox.repository;

import com.paymentflow.sandbox.domain.DecisionLogEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DecisionLogRepository extends JpaRepository<DecisionLogEntry, UUID> {

    Optional<DecisionLogEntry> findByDecisionKey(String decisionKey);
}
