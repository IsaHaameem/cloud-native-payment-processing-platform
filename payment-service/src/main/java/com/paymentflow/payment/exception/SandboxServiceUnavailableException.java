package com.paymentflow.payment.exception;

import com.paymentflow.common.error.CommonErrorCode;
import com.paymentflow.common.exception.PlatformException;

/**
 * Raised when sandbox-service cannot be reached, times out, or fails unexpectedly while
 * advising on an authorization (M17.4). Maps to HTTP 503 — mirrors
 * {@link MerchantServiceUnavailableException} exactly. Always thrown from outside any
 * transaction (D129), before the payment is ever reloaded for mutation, so the payment
 * itself is left untouched and a caller retrying the same Idempotency-Key simply
 * re-attempts cleanly.
 */
public class SandboxServiceUnavailableException extends PlatformException {

    public SandboxServiceUnavailableException(Throwable cause) {
        super(CommonErrorCode.SERVICE_UNAVAILABLE, "Sandbox service is temporarily unavailable.", cause);
    }
}
