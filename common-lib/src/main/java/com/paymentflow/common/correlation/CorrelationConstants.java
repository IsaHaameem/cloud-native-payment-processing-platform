package com.paymentflow.common.correlation;

/**
 * Header and MDC key names for distributed request correlation.
 *
 * <ul>
 *   <li><b>correlationId</b> — flows unchanged across every service hop; identifies a
 *       single logical operation end-to-end.</li>
 *   <li><b>requestId</b> — regenerated per hop; identifies one physical request.</li>
 * </ul>
 */
public final class CorrelationConstants {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String REQUEST_ID_MDC_KEY = "requestId";

    private CorrelationConstants() {
    }
}
