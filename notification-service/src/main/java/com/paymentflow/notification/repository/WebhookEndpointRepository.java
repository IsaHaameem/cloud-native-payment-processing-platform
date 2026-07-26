package com.paymentflow.notification.repository;

import com.paymentflow.notification.domain.WebhookEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every read here is scoped by {@code merchantId} <em>and</em> {@code mode} in the
 * method signature itself (D101/D102) — there is deliberately no
 * {@code findByMerchantId(...)} and no bare {@code findById(...)} wrapper, so a caller
 * cannot reach across a mode boundary by forgetting a filter. A lookup that misses
 * yields empty, which every caller surfaces as 404 rather than 403 (D102): a 403 would
 * confirm the endpoint exists in the other mode, leaking exactly across the boundary
 * this scoping protects.
 *
 * <p>Methods are added as their callers arrive (the platform's standing YAGNI
 * discipline) — M18.2's management API and M18.6's fan-out are the callers these were
 * added for.
 */
public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, UUID> {

    /** The management API's list view — stable registration order, not database order. */
    List<WebhookEndpoint> findByMerchantIdAndModeOrderByCreatedAtAsc(UUID merchantId, String mode);

    /** The single-endpoint read: identity comes from the verified context, never from the path alone (D28). */
    Optional<WebhookEndpoint> findByIdAndMerchantIdAndMode(UUID id, UUID merchantId, String mode);

    /** Fan-out's candidate set (M18.6): a disabled endpoint is never a delivery target. */
    List<WebhookEndpoint> findByMerchantIdAndModeAndEnabledTrue(UUID merchantId, String mode);

    /** Pre-checks the {@code uq_webhook_endpoints_merchant_mode_url} constraint so a duplicate registration is a clean 409. */
    Optional<WebhookEndpoint> findByMerchantIdAndModeAndUrl(UUID merchantId, String mode, String url);

    /** The legacy-adoption gate (M18.9, D135): adopt {@code merchants.webhook_url} only when nothing is registered yet. */
    boolean existsByMerchantIdAndMode(UUID merchantId, String mode);
}
