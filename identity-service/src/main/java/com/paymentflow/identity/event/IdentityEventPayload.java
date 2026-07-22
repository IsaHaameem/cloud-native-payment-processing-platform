package com.paymentflow.identity.event;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Local to identity-service (D36 — only the structural {@code EventEnvelope<T>}
 * wrapper is shared). Covers both {@code EmailVerificationRequested} and
 * {@code PasswordResetRequested}: the recipient email and a link carrying the raw
 * token (notification-service composes the actual email around it and never sees the
 * token any other way — it isn't persisted anywhere outside this service).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IdentityEventPayload(
        UUID userId,
        String recipientEmail,
        String actionLink,
        String expiresAt) {
}
