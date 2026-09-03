package dev.paymentflow.model;

import java.util.Map;

/**
 * One row of your API request log — the platform's own record of a call you made. Bodies and
 * headers are redacted and truncated at the edge before they are ever stored: card numbers, keys
 * and secrets never reach this log. {@code durationMs} is server time only, excluding the network.
 */
public record RequestLogResponse(
        String clientIp,
        String correlationId,
        Long durationMs,
        String errorCode,
        String id,
        String keyId,
        String method,
        String mode,
        String object,
        String occurredAt,
        String path,
        String queryString,
        String requestBody,
        Map<String, String> requestHeaders,
        String requestId,
        String responseBody,
        Long statusCode,
        String userAgent) {}
