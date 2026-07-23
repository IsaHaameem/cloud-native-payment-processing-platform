package com.paymentflow.sandbox.domain;

/** What a test card does at refund time, independent of its authorize/capture behaviour. */
public enum RefundBehaviour {
    SUCCEED,
    FAIL
}
