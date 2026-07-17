package com.paymentflow.common.exception;

import com.paymentflow.common.error.CommonErrorCode;

/** Thrown for malformed or semantically invalid requests. Maps to HTTP 400. */
public class BadRequestException extends PlatformException {

    public BadRequestException(String message) {
        super(CommonErrorCode.BAD_REQUEST, message);
    }
}
