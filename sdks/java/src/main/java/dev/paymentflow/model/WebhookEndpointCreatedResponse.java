package dev.paymentflow.model;

/**
 * What {@code client.webhookEndpoints().create(...)} returns: the {@code endpoint} in the shape
 * every other read returns it in, plus {@code signingSecret} in full.
 *
 * <p><b>The secret is shown exactly once — store it now.</b> Only a hash is kept, so the platform
 * genuinely cannot show it again. A lost one is replaced by rotating, not by recovering.
 */
public record WebhookEndpointCreatedResponse(WebhookEndpointResponse endpoint, String signingSecret) {}
