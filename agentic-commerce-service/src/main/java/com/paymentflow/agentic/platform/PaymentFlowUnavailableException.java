package com.paymentflow.agentic.platform;

import com.paymentflow.agentic.error.AgenticErrorCode;
import com.paymentflow.agentic.error.AgenticException;

/**
 * The platform did not answer, or answered that it could not.
 *
 * <p>The counterpart to {@link PaymentFlowClientException}, and deliberately a different type:
 * a timeout, a connection failure and a 5xx <em>are</em> statements about the platform's
 * health, so unlike a 4xx these <b>do</b> count toward the circuit breaker. Splitting the two
 * is what lets one breaker be useful — a breaker that counted declines would trip on a busy
 * day of ordinary commerce.
 *
 * <p><b>An unavailable platform is not an unknown outcome.</b> A money call that fails this
 * way may or may not have been executed on the far side, and the honest handling is the one
 * this service already has: the action's step is recorded as {@code FAILED} with the derived
 * idempotency key on it, and re-running the same tool re-derives the same key. The platform
 * then either replays what it already did or performs it for the first time. Nothing here
 * needs to guess which happened.
 */
public class PaymentFlowUnavailableException extends AgenticException {

    private final transient Integer httpStatus;

    public PaymentFlowUnavailableException(String message, Throwable cause) {
        super(AgenticErrorCode.PLATFORM_UNAVAILABLE, message, cause);
        this.httpStatus = null;
    }

    public PaymentFlowUnavailableException(int httpStatus, String message) {
        super(AgenticErrorCode.PLATFORM_UNAVAILABLE, message);
        this.httpStatus = httpStatus;
    }

    /**
     * The configuration failure, kept distinct from the reachability failure.
     *
     * <p>A blank API key must not surface as a 401 from the gateway. The two have completely
     * different remedies, and reporting the wrong one sends an operator to read logs in a
     * service that is working perfectly. This is also why the key is checked <em>before</em>
     * the request is sent rather than after it fails: an unconfigured client that sends anyway
     * puts a placeholder credential on the wire and into somebody's proxy log.
     */
    public static AgenticException notConfigured() {
        return new AgenticException(AgenticErrorCode.PLATFORM_NOT_CONFIGURED,
                "No payment-platform API key is configured. Set paymentflow.agentic.platform.api-key "
                        + "(PAYMENTFLOW_AGENT_API_KEY) to a merchant sk_test_ key with the payments:read and "
                        + "payments:write scopes.");
    }

    /** The HTTP status, when there was one. Absent for a timeout or a connection failure. */
    public Integer httpStatus() {
        return httpStatus;
    }
}
