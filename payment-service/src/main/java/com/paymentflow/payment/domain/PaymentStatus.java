package com.paymentflow.payment.domain;

import java.util.Map;
import java.util.Set;

/**
 * The payment lifecycle (per PROJECT_CONTEXT §4): {@code CREATED → AUTHORIZED →
 * CAPTURED → REFUNDED}, plus {@code FAILED}, {@code VOIDED}, and
 * {@code PARTIALLY_REFUNDED}. An explicit transition table — illegal transitions are
 * rejected, never silently coerced.
 *
 * <p>Capture is all-or-nothing (no {@code PARTIALLY_CAPTURED} state exists in the
 * approved lifecycle); refunds may be partial, which is why
 * {@code PARTIALLY_REFUNDED} can transition to itself — further partial refunds stay
 * in that state until the captured amount is fully refunded.
 */
public enum PaymentStatus {
    CREATED,
    AUTHORIZED,
    CAPTURED,
    PARTIALLY_REFUNDED,
    REFUNDED,
    FAILED,
    VOIDED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> LEGAL_TRANSITIONS = Map.of(
            CREATED, Set.of(AUTHORIZED, VOIDED, FAILED),
            AUTHORIZED, Set.of(CAPTURED, VOIDED, FAILED),
            CAPTURED, Set.of(PARTIALLY_REFUNDED, REFUNDED),
            PARTIALLY_REFUNDED, Set.of(PARTIALLY_REFUNDED, REFUNDED),
            REFUNDED, Set.of(),
            FAILED, Set.of(),
            VOIDED, Set.of());

    public boolean canTransitionTo(PaymentStatus target) {
        return LEGAL_TRANSITIONS.get(this).contains(target);
    }

    public boolean isTerminal() {
        return LEGAL_TRANSITIONS.get(this).isEmpty();
    }

    /**
     * The value this status has on the public API wire (M21.5, revision {@code 2026-08-01}).
     *
     * <p>Lowercase {@code snake_case}, matching the platform's position that enum *values*
     * are spelled that way on the wire ({@code ErrorType}, M21.4). Before this revision the
     * Java constant name leaked through Jackson's default serialization, which was never a
     * considered wire form — callers pinned to {@code 2026-07-27} still receive that shape,
     * rebuilt by the gateway's transformation layer rather than produced here.
     */
    public String wireName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
