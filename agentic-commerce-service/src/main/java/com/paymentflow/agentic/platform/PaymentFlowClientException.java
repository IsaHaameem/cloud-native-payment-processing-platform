package com.paymentflow.agentic.platform;

import com.paymentflow.agentic.action.Redactor;
import com.paymentflow.common.exception.PlatformException;

/**
 * The platform answered, and the answer was no.
 *
 * <p>A 4xx is a <b>verdict about this request</b>, not a symptom of an unhealthy platform, and
 * that distinction is load-bearing twice over:
 *
 * <ul>
 *   <li>{@code application.yaml} names this class in the {@code paymentFlow} circuit breaker's
 *       and retry's {@code ignoreExceptions}. A declined card, a refund larger than the
 *       captured amount, an exhausted rate limit — none of them says anything about whether
 *       the platform is reachable, and letting them open the circuit would take the agent
 *       offline because a buyer's card was declined. This is the same D51 rule every other
 *       service in this platform applies.</li>
 *   <li>It is <b>never retried</b>. Retrying a verdict produces the same verdict, and doing so
 *       under a fresh idempotency key would be the one way this service could turn a decline
 *       into a double charge.</li>
 * </ul>
 *
 * <p>The platform's own {@code code} is carried through on {@link #errorCode()} rather than
 * translated — see {@link PlatformErrorCode}. {@code requestId} is the identifier the platform
 * echoes on every response and stamps on every row of the merchant's request log, so it, not
 * the correlation id, is what joins an {@code agent_action_step} to
 * {@code GET /v1/request_logs}.
 */
public class PaymentFlowClientException extends PlatformException {

    private final transient int httpStatus;
    private final transient String requestId;
    private final transient String platformCorrelationId;

    public PaymentFlowClientException(PlatformErrorCode errorCode, String message, String requestId,
                                      String platformCorrelationId) {
        // Redacted on the way in: the message originates outside this service, and it is about
        // to be logged and shown to the model. A message that echoed back a credential from a
        // rejected request would otherwise be written down by the very code reporting the
        // rejection.
        super(errorCode, Redactor.redactText(message));
        this.httpStatus = errorCode.httpStatus();
        this.requestId = requestId;
        this.platformCorrelationId = platformCorrelationId;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /** The platform's stable code, e.g. {@code PAYMENT_NOT_CAPTURABLE}. Reported, never paraphrased. */
    public String platformCode() {
        return errorCode().code();
    }

    /** The platform's per-call identifier. The join key into {@code GET /v1/request_logs}. */
    public String requestId() {
        return requestId;
    }

    public String platformCorrelationId() {
        return platformCorrelationId;
    }

    /**
     * Whether the platform said this exact request is retryable.
     *
     * <p>Exactly one code qualifies: {@code IDEMPOTENCY_CONFLICT}, which means an identical
     * request under the same key is still in flight. It is safe to send again precisely
     * because the key is the same — the second attempt joins the first rather than starting a
     * new one. Nothing else here is retryable, and this method exists so that no caller has to
     * decide that for itself.
     */
    public boolean isRetryableAtSameKey() {
        return "IDEMPOTENCY_CONFLICT".equals(platformCode());
    }
}
