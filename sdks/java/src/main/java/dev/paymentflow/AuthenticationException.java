package dev.paymentflow;

/** The API key is missing, malformed, or not recognised. Retrying will not help. */
public final class AuthenticationException extends PaymentFlowException {

    public AuthenticationException(String message, Detail detail, Throwable cause) {
        super(message, detail, cause);
    }
}
