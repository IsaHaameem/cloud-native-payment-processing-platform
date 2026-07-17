package com.paymentflow.common.error;

/**
 * A machine-readable error classification together with its default HTTP status.
 *
 * <p>Services may define their own domain-specific {@code ErrorCode} implementations
 * (e.g. {@code PAYMENT_ALREADY_CAPTURED}) while reusing the generic ones in
 * {@link CommonErrorCode}. Keeping this an interface avoids forcing every error code
 * into a single shared enum, which would couple all services together.
 */
public interface ErrorCode {

    /** Stable, machine-readable identifier (e.g. {@code NOT_FOUND}). */
    String code();

    /** Default HTTP status associated with this error. */
    int httpStatus();

    /** Human-readable default message; individual throw sites may override it. */
    String defaultMessage();
}
