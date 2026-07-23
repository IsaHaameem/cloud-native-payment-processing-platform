package com.paymentflow.sandbox.domain;

/** What a test card does at capture time, independent of its authorize-time {@link DecisionOutcome}. */
public enum CaptureBehaviour {
    SUCCEED,
    FAIL,
    DEFER
}
