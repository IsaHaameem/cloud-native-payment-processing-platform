package dev.paymentflow;

/**
 * The signature was valid but its timestamp is outside the tolerance window.
 *
 * <p>Distinct from {@link WebhookSignatureException} because it is a different operational
 * problem with a different fix. A valid signature arriving late is usually a replayed delivery —
 * what the timestamp exists to make detectable — but it is also what a clock skewed by minutes
 * looks like. One is an attack and the other is NTP.
 */
public final class WebhookTimestampException extends WebhookVerificationException {

    private final long timestamp;
    private final long skewSeconds;

    public WebhookTimestampException(String message, long timestamp, long skewSeconds) {
        super(message);
        this.timestamp = timestamp;
        this.skewSeconds = skewSeconds;
    }

    /** The {@code t} value the header carried, in epoch seconds. */
    public long timestamp() {
        return timestamp;
    }

    /** How far outside the window it fell, in seconds. Always positive. */
    public long skewSeconds() {
        return skewSeconds;
    }
}
