package com.paymentflow.agentic.action;

/**
 * The life of one tool call, as the action log records it.
 *
 * <p>Every state here is reachable and every one of them is written before the thing it
 * describes happens, not after. That ordering is the whole value of the log: a crash between
 * {@code EXECUTING} and {@code EXECUTED} leaves a row that says an attempt was in flight,
 * which is recoverable. A log written only on success would leave nothing at all, which is
 * the failure mode that makes an audit trail worthless exactly when it is needed.
 *
 * <pre>
 *   PROPOSED ──► VALIDATED ──┬──► REFUSED             (terminal — policy said no)
 *                            ├──► APPROVAL_REQUIRED ──┬──► EXECUTING ──► ...
 *                            │                        └──► REFUSED      (approval denied)
 *                            └──► EXECUTING ──┬──► EXECUTED             (terminal)
 *                                             └──► FAILED               (terminal)
 * </pre>
 */
public enum ActionState {

    /** The model asked for this tool. Nothing has been checked yet. */
    PROPOSED,

    /** The arguments satisfied the tool's schema and were resolved against server-side facts. */
    VALIDATED,

    /** The policy engine refused. No platform call was made, and none will be. */
    REFUSED,

    /** The policy engine requires a human. No platform call has been made. */
    APPROVAL_REQUIRED,

    /** A platform call is in flight. Written before the call, so a crash is visible afterwards. */
    EXECUTING,

    /** The platform accepted the action. What it decided is on the steps, not here. */
    EXECUTED,

    /** The action was attempted and did not complete. {@code failureCode} says why. */
    FAILED;

    public boolean isTerminal() {
        return this == REFUSED || this == EXECUTED || this == FAILED;
    }
}
