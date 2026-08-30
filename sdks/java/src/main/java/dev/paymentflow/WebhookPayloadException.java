package dev.paymentflow;

/**
 * The signature verified, and the body is not an event envelope this SDK can return.
 *
 * <p>Reachable only from a platform defect, and raised rather than papered over because
 * {@code Webhooks.constructEvent} promises a {@code WebhookEvent} whose {@code id}, {@code type}
 * and {@code data} are present.
 */
public final class WebhookPayloadException extends WebhookVerificationException {

    public WebhookPayloadException(String message) {
        super(message);
    }

    public WebhookPayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
