package com.paymentflow.notification.domain;

/**
 * Why the <em>platform</em> disabled an endpoint (M18.1). Deliberately narrow: a
 * merchant disabling their own endpoint through the management API clears
 * {@code enabled} and leaves this {@code null}, so "we turned it off" stays
 * distinguishable from "they turned it off" — which is the distinction that decides
 * whether re-enabling it needs a warning.
 */
public enum EndpointDisableReason {

    /** Auto-disabled after N consecutive delivery failures across distinct events (§4.5). */
    CONSECUTIVE_FAILURES
}
