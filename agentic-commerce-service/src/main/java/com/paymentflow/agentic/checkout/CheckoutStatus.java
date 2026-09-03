package com.paymentflow.agentic.checkout;

import java.util.Map;
import java.util.Set;

/**
 * The checkout lifecycle, as an explicit transition table.
 *
 * <p>Modelled on {@code payment-service}'s {@code PaymentStatus} on purpose, down to the
 * shape of this class: a checkout is the thing that decides how much money moves, so its
 * state deserves the same treatment the payment itself gets. An illegal transition throws
 * rather than being silently coerced.
 *
 * <pre>
 *   OPEN ──► LOCKED ──► PAID        (terminal)
 *    │  │       │
 *    │  │       └─────► OPEN        (payment failed; the quote is usable again)
 *    │  └─────────────► CANCELLED   (terminal)
 *    └────────────────► EXPIRED     (terminal)
 * </pre>
 *
 * <p><b>{@code LOCKED} is the state that does the work.</b> A checkout is locked for the
 * duration of a payment attempt, which is what stops the same quote being paid twice
 * concurrently and stops its contents changing underneath a payment already in flight. It
 * returns to {@code OPEN} if the attempt fails, because a declined card should not destroy
 * the customer's basket.
 */
public enum CheckoutStatus {
    OPEN,
    LOCKED,
    PAID,
    CANCELLED,
    EXPIRED;

    private static final Map<CheckoutStatus, Set<CheckoutStatus>> LEGAL_TRANSITIONS = Map.of(
            OPEN, Set.of(LOCKED, CANCELLED, EXPIRED),
            LOCKED, Set.of(PAID, OPEN, CANCELLED),
            PAID, Set.of(),
            CANCELLED, Set.of(),
            EXPIRED, Set.of());

    public boolean canTransitionTo(CheckoutStatus target) {
        return LEGAL_TRANSITIONS.get(this).contains(target);
    }

    public boolean isTerminal() {
        return LEGAL_TRANSITIONS.get(this).isEmpty();
    }

    /** Whether items may still be added, removed or re-quantified. */
    public boolean isMutable() {
        return this == OPEN;
    }
}
