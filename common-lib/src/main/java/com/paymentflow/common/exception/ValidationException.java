package com.paymentflow.common.exception;

import com.paymentflow.common.error.CommonErrorCode;

/** Thrown for business-rule validation failures detected outside bean validation. Maps to HTTP 400. */
public class ValidationException extends PlatformException {

    public ValidationException(String message) {
        super(CommonErrorCode.VALIDATION_FAILED, message);
    }
}
