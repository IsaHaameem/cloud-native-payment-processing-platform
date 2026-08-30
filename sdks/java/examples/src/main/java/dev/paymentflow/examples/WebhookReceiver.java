package dev.paymentflow.examples;

import dev.paymentflow.WebhookEvent;
import dev.paymentflow.WebhookVerificationException;
import dev.paymentflow.Webhooks;

/**
 * A receiver verifies the signature over the <b>raw</b> bytes before trusting anything. This one
 * takes no API key — {@link Webhooks} is static for exactly that reason.
 */
public final class WebhookReceiver {

    private WebhookReceiver() {}

    /** Call this from your HTTP handler with the raw body and the header value. */
    public static void handle(byte[] rawBody, String signatureHeader) {
        String secret = System.getenv("PAYMENTFLOW_WEBHOOK_SECRET"); // whsec_…
        WebhookEvent event;
        try {
            event = Webhooks.constructEvent(rawBody, signatureHeader, secret);
        } catch (WebhookVerificationException e) {
            // Did not come from PaymentFlow, or did not arrive intact. Respond 400 and stop.
            throw new IllegalArgumentException("unverified webhook", e);
        }

        switch (event.type()) {
            case "payment.captured" -> System.out.println("captured: " + event.dataObject().get("id"));
            case "payment.failed" -> System.out.println("failed: " + event.dataObject().get("failureReason"));
            default -> {
                // Ignore what you do not recognise — new types ship without a new API revision.
            }
        }
        // Dedupe on event.id() — it is stable across retries and replays.
    }
}
