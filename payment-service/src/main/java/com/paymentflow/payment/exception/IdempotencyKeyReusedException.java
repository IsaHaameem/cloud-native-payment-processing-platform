package com.paymentflow.payment.exception;

import com.paymentflow.common.exception.ConflictException;

/** Raised when an Idempotency-Key is reused with a materially different request. Maps to HTTP 409. */
public class IdempotencyKeyReusedException extends ConflictException {

    public IdempotencyKeyReusedException(String idempotencyKey) {
        super("Idempotency-Key '%s' was already used with a different request.".formatted(idempotencyKey));
    }
}
