package com.paymentflow.agentic.action;

/**
 * The outcome of one platform operation inside an action.
 *
 * <p>{@link #REPLAYED} is the one that earns its place. Without it, an idempotent retry and a
 * fresh charge look identical in the log — both are a successful call that returned a
 * payment — and the claim that the agent cannot double-charge would rest on the platform's
 * behaviour rather than on any evidence this service holds. A step marked {@code REPLAYED},
 * next to an earlier step carrying the same {@code idempotency_key}, is that evidence.
 */
public enum StepState {

    /** Recorded but never sent — the action stopped before reaching this operation. */
    NOT_ATTEMPTED,

    /** Written immediately before the HTTP call, so an interrupted call leaves a trace. */
    IN_FLIGHT,

    SUCCEEDED,

    FAILED,

    /**
     * The platform recognised the derived idempotency key and returned the original response
     * instead of performing the operation a second time.
     */
    REPLAYED
}
