package com.paymentflow.payment.exception;

import com.paymentflow.common.exception.ConflictException;
import com.paymentflow.payment.domain.PaymentStatus;

/** Raised when a requested operation would move a payment through an illegal transition. Maps to HTTP 409. */
public class IllegalPaymentStateTransitionException extends ConflictException {

    public IllegalPaymentStateTransitionException(PaymentStatus from, PaymentStatus to) {
        super("Cannot transition payment from '%s' to '%s'.".formatted(from, to));
    }
}
