package com.paymentflow.gateway.version;

/**
 * A caller asked for a revision of the contract this platform does not serve (M21.5).
 *
 * <p>Carries the raw value the caller sent so the error message can quote it back. That
 * matters more than it looks: the two ways to reach this are a typo in a date and a version
 * that has been sunset, and a message that repeats the value distinguishes them at a glance.
 */
public class UnsupportedApiVersionException extends RuntimeException {

    private final transient String requested;

    public UnsupportedApiVersionException(String requested, String message) {
        super(message);
        this.requested = requested;
    }

    /** The value the caller actually sent, quoted back in the error body. */
    public String requested() {
        return requested;
    }
}
