package com.paymentflow.analytics.event;

import java.util.Map;
import java.util.UUID;

/**
 * analytics-service's own local copy of the {@code api.request.events} payload shape
 * (M20.3), per D4/D36 — services share a message <em>shape</em>, never a Java <em>class</em>.
 *
 * <p>D140 drew the line this follows: a <em>frozen public contract</em> that two services must
 * render identically is promoted to {@code common-dto} ({@code CanonicalEventType}); an
 * internal event payload, free to change with its producer, is copied. This is the latter —
 * the gateway owns the shape, and a field it stops sending simply arrives null here rather
 * than breaking a compile in another module.
 */
public record ApiRequestEventPayload(
        UUID merchantId,
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
        Map<String, String> requestHeaders) {
}
