package com.paymentflow.sandbox.domain;

/**
 * The full outcome vocabulary a test card, override, or the simulated acquirer can
 * produce (§4.2/§6). {@code DELAY} means the *authorization* itself is deferred (the
 * synchronous call returns {@code PENDING} at the port, §12; settlement arrives later
 * via {@code sandbox.scheduled.events}, M17.6) — distinct from a card's
 * {@link CaptureBehaviour#DEFER}, which authorizes synchronously but defers only the
 * capture (the seeded catalogue's {@code pm_card_delayedSettlement}, §8.1). No seeded
 * card currently uses {@code DELAY} as its top-level outcome; it is part of the engine's
 * vocabulary for a future authorize-time-deferred scenario, kept alongside
 * {@code CaptureBehaviour.DEFER} rather than added only when a card needs it — the same
 * define-the-vocabulary-now discipline already applied to the webhook-path override
 * scenarios (D131).
 */
public enum DecisionOutcome {
    APPROVE,
    DECLINE,
    ERROR,
    DELAY,
    REQUIRE_ACTION
}
