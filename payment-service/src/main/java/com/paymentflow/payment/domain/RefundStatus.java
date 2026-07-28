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
    FAILED;

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
