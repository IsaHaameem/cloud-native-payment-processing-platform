package com.paymentflow.analytics.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One request as a developer sees it (M20.6).
 *
 * <p>{@code object} carries the same discriminator every public resource in this platform
 * uses (M19.1), so a client can route on type without inspecting the URL it came from.
 *
 * <p>Bodies and headers reach this record already redacted and capped — the scrubbing happened
 * at the gateway before serialization (M20.1/M20.2), not on the way out. That ordering is the
 * milestone's stated mitigation and is why this DTO does no filtering of its own: by the time a
 * value is here, it has been safe for two hops.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RequestLogResponse(
        String object,
        UUID id,
        UUID keyId,
        String mode,
        String method,
        String path,
        String queryString,
        int statusCode,
        long durationMs,
        String clientIp,
        String userAgent,
        String correlationId,
        String requestId,
        String errorCode,
        String requestBody,
        String responseBody,
        Map<String, String> requestHeaders,
        Instant occurredAt) {

    public static final String OBJECT_TYPE = "request_log";
}
