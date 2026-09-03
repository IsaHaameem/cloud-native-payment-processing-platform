package com.paymentflow.agentic.provider;

import java.util.Objects;
import java.util.UUID;

/**
 * What an acquirer is asked to authorize, in provider-neutral terms.
 *
 * <p>Every field is a server-side fact. The amount came from a checkout the platform priced, the
 * currency from the catalogue, the payment id from the platform itself. No part of this record
 * originates with a model, and the adapter that consumes it has no way to reach one.
 *
 * @param decisionKey stable per {@code (paymentId, operation)}, exactly as
 *                    {@code SandboxAuthorizationAdvisor} derives its own. A payment can be
 *                    authorized only once, so every attempt — including a client retry under a
 *                    fresh idempotency key — must resolve to the same acquirer decision rather
 *                    than a fresh roll
 */
public record ProviderAuthorizationRequest(
        String decisionKey,
        UUID paymentId,
        String operation,
        String paymentMethodToken,
        long amountMinor,
        String currency) {

    public ProviderAuthorizationRequest {
        Objects.requireNonNull(decisionKey, "decisionKey");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(currency, "currency");
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("An authorization amount must be positive.");
        }
    }
}
