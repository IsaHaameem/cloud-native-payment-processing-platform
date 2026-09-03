package com.paymentflow.agentic.provider.razorpay;

/**
 * Razorpay answered, and the answer was that this request is wrong.
 *
 * <p>Named in {@code application.yaml} under the {@code razorpay} circuit breaker's and retry's
 * {@code ignoreExceptions}, and for the same reason {@code PaymentFlowClientException} is named
 * under the platform hop's: <b>a 4xx is a verdict about the request, not a symptom of an
 * unhealthy provider</b>. A malformed order or a rejected amount says nothing about whether
 * Razorpay is reachable, and letting one open the circuit would take the provider offline
 * because a single request was bad.
 *
 * <p>The provider's own message is deliberately not carried. It is written by someone else, it
 * is about to be logged, and this service is in no position to promise it does not echo back
 * part of the request — including a header.
 */
public class RazorpayRequestException extends RuntimeException {

    private final transient int httpStatus;
    private final transient String providerCode;

    public RazorpayRequestException(int httpStatus, String providerCode) {
        super("Razorpay rejected the request with HTTP %d (%s).".formatted(httpStatus,
                providerCode == null ? "no code" : providerCode));
        this.httpStatus = httpStatus;
        this.providerCode = providerCode;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /** Razorpay's own error code, sanitised. Reported, never interpreted. */
    public String providerCode() {
        return providerCode;
    }
}
