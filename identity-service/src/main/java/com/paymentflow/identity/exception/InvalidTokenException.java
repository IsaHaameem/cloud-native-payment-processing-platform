package com.paymentflow.identity.exception;

import com.paymentflow.common.exception.UnauthorizedException;

/** Raised when a refresh token is unknown, expired, or already revoked. Maps to HTTP 401. */
public class InvalidTokenException extends UnauthorizedException {

    public InvalidTokenException() {
        super("The refresh token is invalid or has expired.");
    }
}
