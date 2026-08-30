package dev.paymentflow;

/**
 * The platform failed to handle a request it accepted — a 5xx, or a success this SDK could not
 * read. Not the caller's fault, and worth reporting with the {@link #requestId()}.
 *
 * <p>Named to match §7.1's hierarchy across every language. It is unrelated to any generated
 * model; this SDK has no public {@code ApiError} type.
 */
public final class ApiException extends PaymentFlowException {

    public ApiException(String message, Detail detail, Throwable cause) {
        super(message, detail, cause);
    }
}
