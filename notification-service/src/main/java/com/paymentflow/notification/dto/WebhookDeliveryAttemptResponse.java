package com.paymentflow.notification.dto;

import com.paymentflow.notification.domain.AttemptOutcome;

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
        UUID id,
        int attemptNumber,
        AttemptOutcome outcome,
        String requestUrl,
        String requestHeaders,
        String requestBody,
        Integer responseStatus,
        String responseHeaders,
        String responseBody,
        Integer durationMs,
        String error,
        Instant attemptedAt) {
}
