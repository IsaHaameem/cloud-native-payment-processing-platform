package com.paymentflow.sandbox.domain;

/** The three payment-lifecycle operations sandbox-service can be asked to advise on. */
public enum Operation {
    AUTHORIZE,
    CAPTURE,
    REFUND
}
