package dev.paymentflow;

/**
 * The request was understood and rejected: a validation failure, an unknown id, or a state the
 * resource cannot move from. {@link #param()} and {@link #fieldErrors()} say which part.
 */
public final class InvalidRequestException extends PaymentFlowException {

    public InvalidRequestException(String message, Detail detail, Throwable cause) {
        super(message, detail, cause);
    }
}
