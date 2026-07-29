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
        @io.swagger.v3.oas.annotations.media.Schema(
                description = "The endpoint, in the shape every other read returns it in.")
        WebhookEndpointResponse endpoint,

        @io.swagger.v3.oas.annotations.media.Schema(description = """
                The signing secret, in full. **Shown exactly once — store it now.** Every \
                delivery to this endpoint is signed with it, and verifying that signature is \
                the only thing standing between your receiver and anyone who learns its URL. \
                It cannot be retrieved afterwards: only a hash is kept, so the platform \
                genuinely cannot show it again rather than merely declining to. Lost one is \
                replaced by rotating, not by recovering.""",
                example = "whsec_9f2c1e7a4b8d4c3e8a1d2b4f6a8c05d1")
        String signingSecret) {
}
