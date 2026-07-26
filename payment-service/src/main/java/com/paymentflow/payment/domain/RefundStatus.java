package com.paymentflow.payment.domain;

/**
 * A refund's outcome (M19.3). Deliberately two values, not a lifecycle: this platform
 * settles refunds synchronously against the payment FSM, so there is no moment at which
 * a refund is legitimately {@code PENDING} — inventing that state would promise an
 * asynchronous settlement the platform does not perform.
 *
 * <p>{@code FAILED} is unreachable today and present anyway, because an acquirer-backed
 * refund can be rejected downstream and the shape that would have to change then is the
 * enum itself.
 */
public enum RefundStatus {
    SUCCEEDED,
    FAILED
}
