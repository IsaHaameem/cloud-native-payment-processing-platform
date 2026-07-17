package com.paymentflow.identity.exception;

import com.paymentflow.common.exception.ConflictException;

/** Raised when registering an email that already exists. Maps to HTTP 409 (CONFLICT). */
public class EmailAlreadyExistsException extends ConflictException {

    public EmailAlreadyExistsException(String email) {
        super("An account with email '%s' already exists.".formatted(email));
    }
}
