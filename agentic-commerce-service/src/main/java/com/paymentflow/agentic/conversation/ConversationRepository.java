package com.paymentflow.agentic.conversation;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Merchant- and mode-scoped, like every repository in this service. There is no unscoped
 * finder, because a conversation carries a spend budget and reading someone else's is the
 * first step to spending it.
 */
public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    Optional<Conversation> findByIdAndMerchantIdAndMode(UUID id, UUID merchantId, String mode);

    List<Conversation> findByMerchantIdAndModeAndSessionRefOrderByCreatedAtDesc(
            UUID merchantId, String mode, String sessionRef);

    /** The merchant-facing list (G-4), newest first. Page-scoped; still merchant- and mode-bound. */
    List<Conversation> findByMerchantIdAndModeOrderByCreatedAtDesc(UUID merchantId, String mode, Pageable pageable);

    long countByMerchantIdAndMode(UUID merchantId, String mode);

    /** For the metrics summary (G-1): conversations opened in a window. */
    long countByMerchantIdAndModeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            UUID merchantId, String mode, Instant from, Instant to);
}
