package com.paymentflow.merchant.dto;

import java.util.UUID;

/**
 * The internal, service-to-service answer to "which merchant does this user own?"
 * (M23.0, {@code /internal/v1/merchants/by-owner/{ownerUserId}}, D183) — never routed
 * publicly.
 *
 * <p>The gateway's developer-portal path needs exactly what the API-key path already gets
 * from {@link ApiKeyVerifyResponse}: the merchant to scope the request to, and the
 * {@code contactEmail}/{@code webhookUrl} D118 put on the signed context so a downstream
 * consumer never needs a second lookup to learn them.
 *
 * <p>Deliberately <b>not</b> a superset of that response. The rate-limit overrides and the
 * pinned API revision are absent because a session uses neither: session traffic is
 * limited by D24's per-user bucket rather than the per-key one, and the portal always names
 * the current revision explicitly rather than inheriting a pin (§4.7 of the M23
 * specification). Carrying them here would offer the gateway two ways to answer the same
 * question, one of which is wrong.
 */
public record MerchantOwnerLookupResponse(
        UUID merchantId,
        String contactEmail,
        String webhookUrl) {
}
