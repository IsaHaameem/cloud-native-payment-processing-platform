package com.paymentflow.common.exception;

import com.paymentflow.common.error.CommonErrorCode;

/** Thrown when an authenticated caller lacks permission for an action. Maps to HTTP 403. */
public class ForbiddenException extends PlatformException {

    public ForbiddenException(String message) {
        super(CommonErrorCode.FORBIDDEN, message);
    }
}
