package com.paymentflow.payment.exception;

import com.paymentflow.common.exception.BadRequestException;

/** Raised when the caller's account has no merchant profile yet. Maps to HTTP 400. */
public class MerchantNotOnboardedException extends BadRequestException {

    public MerchantNotOnboardedException() {
        super("No merchant profile is associated with this account. Onboard a merchant before creating payments.");
    }
}
