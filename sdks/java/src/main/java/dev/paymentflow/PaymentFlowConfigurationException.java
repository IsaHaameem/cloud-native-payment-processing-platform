package dev.paymentflow;

/**
 * Thrown when the client is constructed with options it cannot work with — a missing API key, a
 * key with surrounding whitespace, an unparseable base URL, a negative timeout.
 *
 * <p>Raised from {@code PaymentFlow.builder().build()}, on the line that built the client, rather
 * than from the first call — by which point the stack trace would point at a payment. Extends
 * {@link PaymentFlowException} so a single {@code catch} still covers it, though it carries no
 * HTTP detail because no request was made.
 */
public final class PaymentFlowConfigurationException extends PaymentFlowException {

    public PaymentFlowConfigurationException(String message) {
        super(message);
    }
}
