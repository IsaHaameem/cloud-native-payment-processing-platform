package com.paymentflow.agentic.approval;

/**
 * The life of one approval request.
 *
 * <pre>
 *   PENDING ──┬──► APPROVED ──► CONSUMED   (terminal — spent on exactly one execution)
 *             ├──► DENIED                  (terminal)
 *             └──► EXPIRED                 (terminal)
 *
 *   APPROVED ─────► EXPIRED                (terminal — granted, never redeemed in time)
 * </pre>
 *
 * <p><b>{@link #CONSUMED} is the state that stops an approval being spent twice.</b> Without
 * it, an approval that stayed {@code APPROVED} after execution would authorise every
 * subsequent retry of the same tool, and "a human approved this refund" would silently become
 * "a human approved this refund, and every one after it".
 *
 * <p><b>{@link #APPROVED} is not permanent.</b> An approval that is granted and then left
 * unredeemed past its expiry moves to {@link #EXPIRED} and can never execute. A grant is a
 * judgement about a situation, and the situation goes stale — the thirty-minute TTL says how
 * long the platform is willing to assume it has not.
 */
public enum ApprovalState {

    /** Awaiting a human. <b>The financial operation has not run and will not run from here.</b> */
    PENDING,

    /** A human said yes. Executable exactly once, and only until the expiry already set. */
    APPROVED,

    /** A human said no. Terminal; the action is refused. */
    DENIED,

    /** The TTL passed before it was decided, or before a grant was redeemed. Terminal. */
    EXPIRED,

    /** Spent. The one execution this approval authorised has been attempted. Terminal. */
    CONSUMED;

    public boolean isTerminal() {
        return this == DENIED || this == EXPIRED || this == CONSUMED;
    }

    /** Whether a financial operation may be executed on the strength of this approval. */
    public boolean isRedeemable() {
        return this == APPROVED;
    }
}
