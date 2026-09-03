package com.paymentflow.agentic.error;

import com.paymentflow.common.exception.PlatformException;

/**
 * The one exception type this service throws for its own domain failures, carrying an
 * {@link AgenticErrorCode}.
 *
 * <p>Extends {@code PlatformException} so that {@code common-lib}'s auto-configured
 * {@code GlobalExceptionHandler} maps it to the platform's standard {@code ApiError}
 * envelope without this service writing an exception handler of its own. One error shape
 * across every service is worth more than a bespoke one here.
 */
public class AgenticException extends PlatformException {

    public AgenticException(AgenticErrorCode errorCode) {
        super(errorCode, errorCode.defaultMessage());
    }

    public AgenticException(AgenticErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public AgenticException(AgenticErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }

    /** The code as its enum, for callers that need to branch on it rather than report it. */
    public AgenticErrorCode agenticErrorCode() {
        return (AgenticErrorCode) errorCode();
    }
}
