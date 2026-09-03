package dev.paymentflow;

/**
 * The rate limit or the daily quota was exceeded.
 *
 * <p>{@link #retryAfterSeconds()} is the platform's own answer to "when may I try again", taken
 * from {@code Retry-After} or {@code RateLimit-Reset}. The SDK's retry loop already waits out a
 * short one; this field is for a caller who has exhausted the retry budget and wants to schedule
 * the work rather than drop it.
 */
public final class RateLimitException extends PaymentFlowException {

    public RateLimitException(String message, Detail detail, Throwable cause) {
        super(message, detail, cause);
    }

    /** Seconds to wait before retrying, when the response said. */
    public Double retryAfterSeconds() {
        return detail().retryAfterSeconds();
    }
}
