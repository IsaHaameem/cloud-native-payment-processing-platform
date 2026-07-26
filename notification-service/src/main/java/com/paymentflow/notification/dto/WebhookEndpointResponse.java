package com.paymentflow.notification.dto;

import com.paymentflow.notification.domain.EndpointDisableReason;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The readable view of an endpoint. Carries {@code signingSecretPrefix} but never the
 * secret itself — that appears exactly once, in
 * {@link WebhookEndpointCreatedResponse}, and is unrecoverable afterwards (§4.9: every
 * secret is stored only as SHA-256, shown once, and displayed thereafter as a prefix).
 *
 * <p>{@code disabledReason} being non-null is how a merchant tells "the platform turned
 * this off because it was failing" from "I turned this off" — the distinction that
 * decides whether re-enabling it needs them to fix something first.
 */
public record WebhookEndpointResponse(
        UUID id,
        String url,
        String description,
        boolean enabled,
        List<String> enabledEvents,
        String signingSecretPrefix,
        String apiVersion,
        int consecutiveFailureCount,
        Instant disabledAt,
        EndpointDisableReason disabledReason,
        boolean migratedFromLegacy,
        Instant createdAt,
        Instant updatedAt) {
}
