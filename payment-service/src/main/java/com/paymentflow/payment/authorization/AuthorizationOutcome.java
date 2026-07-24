package com.paymentflow.payment.authorization;

/**
 * The provider-neutral outcome vocabulary an {@link AuthorizationAdvisor} can return
 * (D132). {@code PENDING} is part of this contract from the start — a real
 * authorization can be deferred — but no adapter produces it until M17.6 wires deferred
 * outcomes end to end; {@link com.paymentflow.payment.service.PaymentService} has no
 * handling for it yet.
 */
public enum AuthorizationOutcome {
    APPROVED,
    DECLINED,
    ERROR,
    PENDING
}
