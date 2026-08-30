package dev.paymentflow;

/** The key is valid but not allowed to do this — a missing scope, or the wrong mode. */
public final class PermissionException extends PaymentFlowException {

    public PermissionException(String message, Detail detail, Throwable cause) {
        super(message, detail, cause);
    }
}
