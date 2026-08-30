package dev.paymentflow;

import dev.paymentflow.model.ApiFieldError;

import java.util.List;

/**
 * The base class every error this SDK throws extends.
 *
 * <p>Catching this and nothing else is a complete, correct handler — which is the point. An
 * integrator who wants to distinguish cases narrows to a subclass; one who does not is still
 * safe. §7.1 fixes the hierarchy across every language; Java spells the classes
 * {@code ...Exception} where Node spells them {@code ...Error}, and that is the only difference.
 *
 * <p>Unchecked, deliberately. A checked exception on every call would push {@code try}/{@code
 * catch} into code whose only honest response to most failures is to let them propagate.
 */
public class PaymentFlowException extends RuntimeException {

    /**
     * Everything the transport learned about a failed call. Every field is nullable: a 502 from a
     * load balancer that never reached the platform has no {@code type}, no body, and often no
     * JSON at all.
     */
    public record Detail(
            Integer statusCode,
            String type,
            String code,
            String param,
            List<ApiFieldError> fieldErrors,
            String requestId,
            String correlationId,
            String docUrl,
            Integer attempts,
            Double retryAfterSeconds) {

        public static Detail empty() {
            return new Detail(null, null, null, null, null, null, null, null, null, null);
        }
    }

    private final transient Detail detail;

    public PaymentFlowException(String message) {
        this(message, Detail.empty(), null);
    }

    public PaymentFlowException(String message, Detail detail, Throwable cause) {
        super(message, cause);
        this.detail = detail == null ? Detail.empty() : detail;
    }

    /** The HTTP status, or {@code null} when no response was received. */
    public Integer statusCode() {
        return detail.statusCode();
    }

    /** The platform's classification of the error — the field to branch on, when present. */
    public String type() {
        return detail.type();
    }

    /** The stable machine-readable code, such as {@code PAYMENT_NOT_CAPTURABLE}. */
    public String code() {
        return detail.code();
    }

    /** The single offending parameter, when there is exactly one. */
    public String param() {
        return detail.param();
    }

    /** Field-level validation failures, when more than one field was rejected. */
    public List<ApiFieldError> fieldErrors() {
        return detail.fieldErrors();
    }

    /** Identifies this one HTTP call. Quote it in a support request. */
    public String requestId() {
        return detail.requestId();
    }

    /** Identifies the whole distributed trace, which may span several services. */
    public String correlationId() {
        return detail.correlationId();
    }

    /** Where to read about this specific code. */
    public String docUrl() {
        return detail.docUrl();
    }

    /** How many HTTP attempts were made before this error was raised. At least 1. */
    public Integer attempts() {
        return detail.attempts();
    }

    /** The full detail record. */
    public Detail detail() {
        return detail;
    }
}
