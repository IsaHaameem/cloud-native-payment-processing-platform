package dev.paymentflow;

/**
 * A webhook delivery that could not be trusted. Separate from the HTTP hierarchy because it
 * describes the opposite direction — raised while <em>receiving</em> something the platform
 * sent, not while calling it. Nothing here has a {@link #statusCode()}.
 */
public sealed class WebhookVerificationException extends PaymentFlowException
        permits WebhookSignatureException, WebhookTimestampException, WebhookPayloadException {

    public WebhookVerificationException(String message) {
        super(message);
    }

    public WebhookVerificationException(String message, Throwable cause) {
        super(message, Detail.empty(), cause);
    }
}
