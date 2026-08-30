package dev.paymentflow.internal;

/**
 * A JSON document could not be parsed, or could not be mapped to the record a caller asked for.
 *
 * <p>Internal. The transport catches this and turns it into the SDK's public error: a malformed
 * <em>response</em> becomes an {@code ApiException} ("the API returned a body that is not JSON"),
 * and a malformed <em>webhook</em> payload becomes a {@code WebhookPayloadException}.
 */
public final class JsonException extends RuntimeException {

    public JsonException(String message) {
        super(message);
    }

    public JsonException(String message, Throwable cause) {
        super(message, cause);
    }
}
