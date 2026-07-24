package com.paymentflow.sandbox.event;

import java.util.UUID;

/**
 * The JSON shape published on {@code sandbox.scheduled.events} (M17.6, §4.2).
 * Deliberately local to sandbox-service, not shared — payment-service defines its own
 * copy of this shape (schema-per-service applied to messaging contracts, D4, the same
 * discipline {@code PaymentEventPayload} documents for {@code payment.events}).
 *
 * <p>Carries only {@code paymentId}/{@code operation}/{@code outcome} — no sandbox
 * vocabulary (test-card identity, override id, latency) crosses this boundary either;
 * {@code mode} rides the envelope (M16 convention), and the consumer needs nothing else
 * to apply the deferred transition through the same FSM guard a synchronous call uses.
 */
public record SandboxScheduledOutcomePayload(UUID paymentId, String operation, String outcome) {
}
