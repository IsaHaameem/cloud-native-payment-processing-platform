package dev.paymentflow.model;

/**
 * One attempt at delivering a webhook. {@code requestBody} is byte-for-byte what the signature
 * was computed over; {@code requestHeaders} includes the {@code PaymentFlow-Signature} this
 * platform sent (a signature is not a secret — comparing it against the one you computed is how
 * a verification failure is diagnosed). {@code error} is set only when the receiver was never
 * reached; {@code responseStatus} only when it was.
 */
public record WebhookDeliveryAttemptResponse(
        Long attemptNumber,
        String attemptedAt,
        Long durationMs,
        String error,
        String id,
        String outcome,
        String requestBody,
        String requestHeaders,
        String requestUrl,
        String responseBody,
        String responseHeaders,
        Long responseStatus) {}
