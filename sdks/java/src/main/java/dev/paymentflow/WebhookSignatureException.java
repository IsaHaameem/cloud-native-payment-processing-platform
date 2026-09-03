package dev.paymentflow;

/**
 * The signature header was malformed, or no signature in it matched.
 *
 * <p><b>Treat this as hostile.</b> A body that fails verification did not come from PaymentFlow,
 * or did not arrive intact, and either way must not be acted on.
 */
public final class WebhookSignatureException extends WebhookVerificationException {

    public WebhookSignatureException(String message) {
        super(message);
    }
}
