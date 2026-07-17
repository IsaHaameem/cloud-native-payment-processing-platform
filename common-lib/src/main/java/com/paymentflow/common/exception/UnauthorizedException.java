package com.paymentflow.common.exception;

import com.paymentflow.common.error.CommonErrorCode;

/** Thrown when authentication is missing or invalid. Maps to HTTP 401. */
public class UnauthorizedException extends PlatformException {

    public UnauthorizedException(String message) {
        super(CommonErrorCode.UNAUTHORIZED, message);
    }
}
