package com.paymentflow.merchant.exception;

import com.paymentflow.common.exception.ConflictException;

import java.util.UUID;

/** Raised when a user who already owns a merchant profile tries to onboard again. Maps to HTTP 409. */
public class MerchantAlreadyExistsException extends ConflictException {

    public MerchantAlreadyExistsException(UUID ownerUserId) {
        super("User '%s' has already onboarded a merchant.".formatted(ownerUserId));
    }
}
