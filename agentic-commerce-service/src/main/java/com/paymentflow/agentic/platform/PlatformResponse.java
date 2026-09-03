package com.paymentflow.agentic.platform;

/**
 * A platform answer together with the identifiers needed to record the step that produced it.
 *
 * <p>The body alone is not enough for the action log. {@code agent_action_steps} stores an HTTP
 * status and a request id, and both come from the response envelope rather than the payload —
 * without them a step would say what happened but not which of the platform's own log rows
 * said so, and the join into {@code GET /v1/request_logs} would be gone.
 *
 * @param requestId     the platform's per-call identifier, from {@code X-Request-Id}. The join
 *                      key into the merchant's request log
 * @param correlationId the platform's echo of the {@code X-Correlation-Id} this service sent.
 *                      Read back purely to confirm it survived the hop; the value this service
 *                      logs is always the one it chose
 */
public record PlatformResponse<T>(T body, int httpStatus, String requestId, String correlationId) {

    /**
     * Re-shapes the body while keeping the envelope intact.
     *
     * <p>Exists so a caller that needs a different container — an array become a list — does not
     * have to rebuild the response and risk dropping the request id on the way, which is the
     * field the action log cannot be reconstructed without.
     */
    public <R> PlatformResponse<R> map(java.util.function.Function<T, R> mapper) {
        return new PlatformResponse<>(mapper.apply(body), httpStatus, requestId, correlationId);
    }
}
