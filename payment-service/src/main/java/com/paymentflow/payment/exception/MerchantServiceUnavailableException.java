package com.paymentflow.payment.exception;

import com.paymentflow.common.error.CommonErrorCode;
import com.paymentflow.common.exception.PlatformException;

/**
 * Raised when merchant-service cannot be reached or fails unexpectedly. Maps to HTTP
 * 503. No retry/circuit-breaker/fallback behavior yet — Resilience4j is its own
 * milestone (M8), deliberately not pulled forward here.
 */
public class MerchantServiceUnavailableException extends PlatformException {

    public MerchantServiceUnavailableException(Throwable cause) {
        super(CommonErrorCode.SERVICE_UNAVAILABLE, "Merchant service is temporarily unavailable.", cause);
    }
}
