package com.paymentflow.agentic.approval;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Merchant- and mode-scoped, like every repository in this service.
 *
 * <p>The scoping matters more here than almost anywhere else: an unscoped {@code findById}
 * would let one merchant's approval be quoted while executing another merchant's refund, and
 * the binding check would then be comparing against the wrong row entirely.
 */
public interface ApprovalRepository extends JpaRepository<Approval, UUID> {

    Optional<Approval> findByIdAndMerchantIdAndMode(UUID id, UUID merchantId, String mode);

    /** One approval per action, guaranteed by {@code uq_approvals_action}. */
    Optional<Approval> findByAgentActionId(UUID agentActionId);

    boolean existsByAgentActionId(UUID agentActionId);

    List<Approval> findByMerchantIdAndModeAndStateOrderByCreatedAtDesc(
            UUID merchantId, String mode, ApprovalState state, Pageable pageable);

    List<Approval> findByConversationIdOrderByCreatedAtDesc(UUID conversationId);

    /** For the metrics summary (G-1). */
    long countByMerchantIdAndModeAndState(UUID merchantId, String mode, ApprovalState state);

    long countByMerchantIdAndModeAndStateAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            UUID merchantId, String mode, ApprovalState state, java.time.Instant from, java.time.Instant to);
}
