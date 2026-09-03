package com.paymentflow.agentic.action;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Merchant- and mode-scoped, like every repository in this service. */
public interface AgentActionRepository extends JpaRepository<AgentAction, UUID> {

    Optional<AgentAction> findByIdAndMerchantIdAndMode(UUID id, UUID merchantId, String mode);

    List<AgentAction> findByConversationIdOrderByCreatedAtDesc(UUID conversationId);

    List<AgentAction> findByMerchantIdAndModeOrderByCreatedAtDesc(UUID merchantId, String mode, Pageable pageable);

    List<AgentAction> findByMerchantIdAndModeAndPaymentIdOrderByCreatedAtDesc(
            UUID merchantId, String mode, UUID paymentId, Pageable pageable);

    long countByMerchantIdAndMode(UUID merchantId, String mode);

    long countByMerchantIdAndModeAndPaymentId(UUID merchantId, String mode, UUID paymentId);

    long countByMerchantIdAndModeAndState(UUID merchantId, String mode, ActionState state);

    long countByMerchantIdAndModeAndPolicyDecision(
            UUID merchantId, String mode, com.paymentflow.agentic.policy.PolicyDecision policyDecision);

    /** For the metrics summary (G-1): actions recorded in a window. */
    long countByMerchantIdAndModeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            UUID merchantId, String mode, Instant from, Instant to);

    long countByMerchantIdAndModeAndStateAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            UUID merchantId, String mode, ActionState state, Instant from, Instant to);

    long countByMerchantIdAndModeAndPolicyDecisionAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            UUID merchantId, String mode, com.paymentflow.agentic.policy.PolicyDecision policyDecision,
            Instant from, Instant to);

    long countByMerchantIdAndModeAndPaymentIdIsNotNullAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            UUID merchantId, String mode, Instant from, Instant to);

    /**
     * The join into the platform's own audit trail. One correlation id per action, sent as
     * {@code X-Correlation-Id} and carried by the platform into its event envelope and its
     * request log — so this is what ties an agent decision to {@code GET /v1/events}.
     */
    List<AgentAction> findByCorrelationId(String correlationId);

    List<AgentAction> findByPaymentId(UUID paymentId);
}
