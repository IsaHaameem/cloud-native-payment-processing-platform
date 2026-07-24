package com.paymentflow.payment.authorization.sandbox;

import java.util.UUID;

/**
 * The wire shape of {@code sandbox.scheduled.events} (M17.6) — a payment-service-local
 * projection, same rationale as {@link SandboxDecisionRequest}/{@link
 * SandboxDecisionResponse} (D4's schema-per-service philosophy applied to messaging
 * contracts). Never leaves this package: {@link SandboxScheduledEventListener} is the
 * only code that ever sees it, and it translates {@code operation}/{@code outcome} into
 * a plain call on {@code PaymentService} — nothing sandbox-shaped crosses into the rest
 * of payment-service.
 */
public record SandboxScheduledOutcomePayload(UUID paymentId, String operation, String outcome) {
}
