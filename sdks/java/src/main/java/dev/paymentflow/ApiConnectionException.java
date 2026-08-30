package dev.paymentflow;

/**
 * The request never produced a response: DNS failure, connection reset, or the client-side
 * timeout elapsing. {@link #getCause()} carries the underlying failure. There is no
 * {@link #statusCode()} because there was no reply — which is also why this is the one error
 * where "did it happen?" is genuinely unknown, and why the idempotency key matters most here.
 */
public final class ApiConnectionException extends PaymentFlowException {

    public ApiConnectionException(String message, Detail detail, Throwable cause) {
        super(message, detail, cause);
    }
}
