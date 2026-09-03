package com.paymentflow.agentic.provider.razorpay;

/**
 * Razorpay could not be reached, or answered that it could not answer.
 *
 * <p>The counterpart to {@link RazorpayRequestException}, and a different type for the same
 * reason the platform hop splits its two: this one <em>is</em> a statement about the provider's
 * health, so unlike a 4xx it counts toward the circuit breaker. A breaker that counted rejected
 * requests would trip on a busy day of ordinary declines.
 */
public class RazorpayUnavailableException extends RuntimeException {

    public RazorpayUnavailableException(String message) {
        super(message);
    }

    public RazorpayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
