package com.paymentflow.payment.authorization;

import java.util.UUID;

/**
 * Everything an {@link AuthorizationAdvisor} needs to decide one payment's
 * authorization, and nothing more (D132) — no provider-specific concept (a decision
 * key, a test-card identity, latency) crosses this boundary. {@code paymentMethodToken}
 * is nullable (M17.3, D130); a provider with no concept of a test token can simply
 * ignore it.
 */
public record AuthorizationRequest(
        UUID paymentId,
        UUID merchantId,
        String mode,
        String paymentMethodToken,
        long amountMinor,
        String currency) {
}
