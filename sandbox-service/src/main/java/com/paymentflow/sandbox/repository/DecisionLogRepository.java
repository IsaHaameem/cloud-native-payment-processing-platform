package com.paymentflow.sandbox.repository;

import com.paymentflow.sandbox.domain.DecisionLogEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DecisionLogRepository extends JpaRepository<DecisionLogEntry, UUID> {

    Optional<DecisionLogEntry> findByDecisionKey(String decisionKey);

    /** M17.8: the query API's list endpoint — served by idx_decision_log_merchant_mode_time (M17.2). */
    Page<DecisionLogEntry> findByMerchantIdAndModeOrderByCreatedAtDesc(UUID merchantId, String mode, Pageable pageable);

    /** M17.8: "why was this payment declined?" — every decision recorded for one payment, newest first. */
    List<DecisionLogEntry> findByMerchantIdAndModeAndPaymentIdOrderByCreatedAtDesc(
            UUID merchantId, String mode, UUID paymentId);
}
