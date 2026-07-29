package com.paymentflow.notification.dto;

import com.paymentflow.notification.domain.AttemptOutcome;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * One attempt as the delivery log renders it (M18.8, §4.5): the request actually sent and
 * the response actually received.
 *
 * <p>The request headers are returned including the {@code PaymentFlow-Signature} the
 * platform sent, which is deliberate and safe — the header is a signature, not a secret,
 * and a merchant debugging a verification failure needs to compare the value we sent
 * against the one they computed. The signing secret itself never appears here or anywhere
 * else after creation.
 */
public record WebhookDeliveryAttemptResponse(
        @Schema(description = "Unique identifier for this attempt.")
        UUID id,

        @Schema(description = "Which attempt this was, starting at 1.", example = "1")
        int attemptNumber,

        @Schema(description = "What happened: the receiver accepted it, rejected it, or was "
                + "never reached.", example = "SUCCESS")
        AttemptOutcome outcome,

        @Schema(description = "The URL this attempt was sent to.",
                example = "https://example.com/webhooks/paymentflow")
        String requestUrl,

        @Schema(description = """
                The headers sent, **including the `PaymentFlow-Signature`** this platform \
                computed. That is deliberate and safe — a signature is not a secret, and \
                comparing the value sent against the one you computed is how a verification \
                failure gets diagnosed. The signing secret itself never appears here.""")
        String requestHeaders,

        @Schema(description = "The body sent — byte for byte what the signature was computed "
                + "over.")
        String requestBody,

        @Schema(description = "The HTTP status the receiver returned. Absent when it was "
                + "never reached.", example = "200")
        Integer responseStatus,

        @Schema(description = "The headers the receiver returned.")
        String responseHeaders,

        @Schema(description = "The body the receiver returned, truncated. Often the only "
                + "explanation of a rejection.")
        String responseBody,

        @Schema(description = "How long the attempt took, in milliseconds.", example = "128")
        Integer durationMs,

        @Schema(description = """
                Why the attempt failed before it got a response — a DNS failure, a refused \
                connection, a timeout, or an address the egress guard blocked. Absent when \
                the receiver answered at all, even with an error status.""")
        String error,

        @Schema(description = "When the attempt was made, as RFC 3339.")
        Instant attemptedAt) {
}
