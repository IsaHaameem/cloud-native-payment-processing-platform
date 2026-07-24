package com.paymentflow.payment.authorization;

/**
 * The port {@code PaymentService} depends on for an authorization verdict (M17.4,
 * D132) — a one-method, acquirer-neutral contract. {@link
 * com.paymentflow.payment.authorization.sandbox.SandboxAuthorizationAdvisor} is the
 * only adapter today; a real acquirer integration would be a second one, added without
 * {@code PaymentService} changing at all. No provider-selection strategy or
 * multi-provider routing exists or is planned — the port's value is isolating
 * provider-specific vocabulary from the platform's frozen public contracts
 * ({@code PaymentResponse}, {@code PaymentEventPayload}), not swapping providers at
 * runtime.
 */
public interface AuthorizationAdvisor {

    AuthorizationDecision advise(AuthorizationRequest request);
}
