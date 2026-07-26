package com.paymentflow.notification.domain;

/**
 * The result of one webhook delivery attempt (M18.1). Four outcomes rather than a
 * boolean, because the delivery log exists to answer "why did my webhook not arrive?"
 * and each of these implies a different answer for the merchant.
 */
public enum AttemptOutcome {

    /** The endpoint answered 2xx. */
    SUCCEEDED,

    /** The endpoint answered, but with a non-2xx status — their bug, and their status code proves it. */
    FAILED_STATUS,

    /** Connect failure, read timeout, or TLS error — no response was ever received, so there is no status to show. */
    FAILED_TRANSPORT,

    /**
     * The egress guard (M18.5) refused to make the call at all — the URL resolved into a
     * blocked range, or violated the scheme policy. Never conflated with
     * {@link #FAILED_TRANSPORT}: a merchant debugging an endpoint that was never
     * contacted needs to be told that, not shown a connection error that implies we
     * tried.
     */
    BLOCKED;

    public boolean isSuccess() {
        return this == SUCCEEDED;
    }
}
