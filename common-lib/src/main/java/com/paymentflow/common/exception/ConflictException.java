package com.paymentflow.common.exception;

import com.paymentflow.common.error.CommonErrorCode;

/** Thrown when a request conflicts with current resource state (e.g. duplicate). Maps to HTTP 409. */
public class ConflictException extends PlatformException {

    public ConflictException(String message) {
        super(CommonErrorCode.CONFLICT, message);
    }
}
