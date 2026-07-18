package com.paymentflow.payment.exception;

import com.paymentflow.common.exception.ConflictException;

/** Raised when another request with the same Idempotency-Key is currently being processed. Maps to HTTP 409. */
public class IdempotencyKeyInFlightException extends ConflictException {

    public IdempotencyKeyInFlightException(String idempotencyKey) {
        super("A request with Idempotency-Key '%s' is already being processed.".formatted(idempotencyKey));
    }
}
