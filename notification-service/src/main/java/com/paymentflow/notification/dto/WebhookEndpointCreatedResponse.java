package com.paymentflow.notification.dto;

/**
 * The one and only response that carries a raw {@code whsec_} signing secret —
 * returned at registration and at rotation, never retrievable afterwards. Mirrors
 * merchant-service's {@code ApiKeyIssuedResponse} exactly, including the reason: only
 * the SHA-256 hash is persisted, so the platform genuinely cannot show it again rather
 * than merely declining to (§4.9).
 *
 * <p>Modelled as a wrapper around {@link WebhookEndpointResponse} rather than a flat
 * record duplicating all of its fields, so the endpoint's shape is defined in exactly
 * one place and the two responses cannot drift apart.
 */
public record WebhookEndpointCreatedResponse(
        WebhookEndpointResponse endpoint,
        String signingSecret) {
}
