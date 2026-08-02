package com.paymentflow.gateway.security.session;

import java.util.UUID;

/**
 * The gateway's own local copy of merchant-service's
 * {@code /internal/v1/merchants/by-owner/{ownerUserId}} response shape (M23.0) — the same
 * schema-per-service-messaging convention (D36) {@code ApiKeyVerifyResult} applies to the
 * verify contract.
 *
 * <p>Three fields, and no more. Everything else the API-key path resolves — the key, its
 * scopes, the merchant's rate-limit overrides, their pinned API revision — either does not
 * exist for a session or is deliberately not honoured for one (§4.7 of the M23
 * specification).
 */
public record SessionMerchantResult(
        UUID merchantId,
        String contactEmail,
        String webhookUrl) {
}
