package com.paymentflow.gateway.logging;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;
import java.util.UUID;

/**
 * The payload of an {@code api.request.events} message (M20.2, §4.7) — one API request as
 * the developer will eventually see it in {@code GET /v1/request_logs}.
 *
 * <p>Per D4/D36 this is the gateway's own local copy of the message shape;
 * analytics-service defines its own for the consuming side. The two are deliberately not a
 * shared class: this is an internal event payload, free to change with its producer, and
 * D140 drew that line explicitly — a <em>frozen public contract</em> is promoted to
 * {@code common-dto} ({@code CanonicalEventType}), an internal payload is copied.
 *
 * <p>Every string that could carry a credential — the query string, the bodies, the headers
 * — has already been through {@link com.paymentflow.common.redaction.RequestRedactor} by the
 * time it reaches this record. Redaction happens before construction, not before
 * serialization of the envelope, so there is no window in which an unredacted value exists
 * as a serialized object (M20's risk table).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
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
