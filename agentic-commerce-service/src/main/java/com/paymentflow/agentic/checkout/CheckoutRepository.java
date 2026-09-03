package com.paymentflow.agentic.checkout;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Merchant- and mode-scoped, with no unscoped finder — the same rule and the same reason as
 * {@code ProductRepository}. A checkout is the amount a payment will charge; a cross-tenant
 * read of one is the worst thing this service could do.
 */
public interface CheckoutRepository extends JpaRepository<Checkout, UUID> {

    Optional<Checkout> findByIdAndMerchantIdAndMode(UUID id, UUID merchantId, String mode);

    List<Checkout> findByMerchantIdAndModeAndSessionRefOrderByCreatedAtDesc(
            UUID merchantId, String mode, String sessionRef);

    /** The merchant-facing list (G-2), newest first. Page-scoped; still merchant- and mode-bound. */
    List<Checkout> findByMerchantIdAndModeOrderByCreatedAtDesc(UUID merchantId, String mode, Pageable pageable);

    long countByMerchantIdAndMode(UUID merchantId, String mode);

    List<Checkout> findByConversationIdOrderByCreatedAtDesc(UUID conversationId);

    /**
     * Finds the checkout a payment paid. Used by the provider-decision path, which is handed a
     * payment id by payment-service and has to recover the commerce context behind it —
     * remembering that the correlation id does not survive that hop (project_3_context.md
     * §28.1 Finding 2), so the payment id is the only anchor available.
     */
    Optional<Checkout> findByPaymentId(UUID paymentId);
}
