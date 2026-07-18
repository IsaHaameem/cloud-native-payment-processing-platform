package com.paymentflow.payment.exception;

import com.paymentflow.common.exception.BadRequestException;

/** Raised when a refund amount is non-positive or exceeds what remains to be refunded. Maps to HTTP 400. */
public class InvalidRefundAmountException extends BadRequestException {

    public InvalidRefundAmountException(long requestedAmountMinor, long remainingAmountMinor) {
        super("Refund amount %d must be positive and not exceed the remaining refundable amount of %d."
                .formatted(requestedAmountMinor, remainingAmountMinor));
    }
}
