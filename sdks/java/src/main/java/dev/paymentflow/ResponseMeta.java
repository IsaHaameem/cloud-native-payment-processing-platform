package dev.paymentflow;

/**
 * Everything a caller can learn about the exchange, beyond the body. Attached to a {@code Page};
 * for a single object, read {@link PaymentFlowException#requestId()} off a thrown exception.
 *
 * <ul>
 *   <li>{@code requestId} — identifies this one HTTP call, and keys the matching
 *       {@code GET /v1/request_logs} row.</li>
 *   <li>{@code correlationId} — identifies the whole distributed trace.</li>
 *   <li>{@code apiVersion} — the dated revision that answered; {@code null} when the request was
 *       refused at the edge.</li>
 *   <li>{@code deprecated} — {@code true} when that revision has been superseded.</li>
 *   <li>{@code rateLimit} — daily-quota telemetry, when the response was measured.</li>
 *   <li>{@code attempts} — how many HTTP attempts this call took; 1 when it succeeded first time.</li>
 * </ul>
 */
public record ResponseMeta(
        int statusCode,
        String requestId,
        String correlationId,
        String apiVersion,
        boolean deprecated,
        RateLimitMeta rateLimit,
        int attempts) {}
