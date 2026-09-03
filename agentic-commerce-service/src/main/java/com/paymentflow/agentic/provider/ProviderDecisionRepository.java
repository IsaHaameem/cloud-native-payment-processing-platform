package com.paymentflow.agentic.provider;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Persisted provider decisions (G-6), merchant- and mode-scoped like every repository in this
 * service. There is no unscoped finder: a provider decision names a payment and an outcome, and
 * a cross-tenant read of one would let a merchant see another's payment result.
 */
public interface ProviderDecisionRepository extends JpaRepository<ProviderDecisionRecord, UUID> {

    /** Persisting is idempotent on the decision key payment-service supplies per attempt. */
    boolean existsByDecisionKey(String decisionKey);

    List<ProviderDecisionRecord> findByMerchantIdAndModeAndPaymentIdOrderByCreatedAtDesc(
            UUID merchantId, String mode, UUID paymentId);

    List<ProviderDecisionRecord> findByMerchantIdAndModeOrderByCreatedAtDesc(
            UUID merchantId, String mode, Pageable pageable);

    long countByMerchantIdAndMode(UUID merchantId, String mode);
}
