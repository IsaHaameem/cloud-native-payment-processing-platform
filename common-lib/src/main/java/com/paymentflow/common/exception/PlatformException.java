package com.paymentflow.common.exception;

import com.paymentflow.common.error.ErrorCode;

import java.util.Objects;

/**
 * Base type for all domain/application exceptions. Every subtype carries an
 * {@link ErrorCode}, which the global exception handler maps to an HTTP status and a
 * stable machine-readable error code on the wire.
 */
public abstract class PlatformException extends RuntimeException {

    // transient: RuntimeException is Serializable but ErrorCode implementations need not be.
    private final transient ErrorCode errorCode;

    protected PlatformException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    protected PlatformException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
