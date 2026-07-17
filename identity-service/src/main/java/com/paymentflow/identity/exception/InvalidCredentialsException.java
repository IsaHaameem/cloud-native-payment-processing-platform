package com.paymentflow.identity.exception;

import com.paymentflow.common.exception.UnauthorizedException;

/**
 * Raised on failed login. The message is deliberately generic so it does not reveal
 * whether the email exists (prevents account enumeration). Maps to HTTP 401.
 */
public class InvalidCredentialsException extends UnauthorizedException {

    public InvalidCredentialsException() {
        super("Invalid email or password.");
    }
}
