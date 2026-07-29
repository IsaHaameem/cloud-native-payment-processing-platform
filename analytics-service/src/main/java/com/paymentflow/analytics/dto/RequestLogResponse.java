package com.paymentflow.analytics.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

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
        @Schema(description = "Always `request_log`.", example = "request_log")
        String object,

        @Schema(description = "Unique identifier for this log entry.")
        UUID id,

        @Schema(description = "The API key the request was made with. Absent for a key that "
                + "has since been deleted.")
        UUID keyId,

        @Schema(description = "Whether the request was made with a `test` or `live` key.",
                allowableValues = {"test", "live"}, example = "test")
        String mode,

        @Schema(description = "The HTTP method.", example = "POST")
        String method,

        @Schema(description = "The path requested, as sent.", example = "/v1/payments")
        String path,

        @Schema(description = "The query string, if any, with sensitive values already "
                + "redacted.", example = "limit=25")
        String queryString,

        @Schema(description = "The HTTP status returned.", example = "201")
        int statusCode,

        @Schema(description = "How long the platform took to answer, in milliseconds. Server "
                + "time only — it excludes the network.", example = "42")
        long durationMs,

        @Schema(description = "The client address the request arrived from.",
                example = "203.0.113.7")
        String clientIp,

        @Schema(description = "The `User-Agent` header, useful for telling one of your "
                + "services from another.", example = "paymentflow-node/1.2.0")
        String userAgent,

        @Schema(description = "Identifies the whole distributed trace this call belongs to.")
        String correlationId,

        @Schema(description = "Identifies this one HTTP call — the same value the response's "
                + "error body carried, if it failed.", example = "req_9f2c1e7a4b8d")
        String requestId,

        @Schema(description = "The catalogued error code, when the request failed. Absent for "
                + "a successful request.", example = "PAYMENT_NOT_CAPTURABLE")
        String errorCode,

        @Schema(description = """
                The request body, **already redacted and truncated** at the edge before it \
                was ever stored — card numbers, keys and secrets never reach this log.""")
        String requestBody,

        @Schema(description = "The response body, redacted and truncated on the same terms as "
                + "the request body.")
        String responseBody,

        @Schema(description = "The request headers, with credential-bearing values redacted.")
        Map<String, String> requestHeaders,

        @Schema(description = "When the request arrived, as RFC 3339.")
        Instant occurredAt) {

    public static final String OBJECT_TYPE = "request_log";
}
