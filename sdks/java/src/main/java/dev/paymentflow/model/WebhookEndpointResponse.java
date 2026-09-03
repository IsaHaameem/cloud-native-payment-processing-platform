package dev.paymentflow.model;

import java.util.List;
import java.util.Map;

/**
 * A registered webhook endpoint. {@code disabledReason}
 * ({@link Vocabularies#WEBHOOK_ENDPOINT_RESPONSE_DISABLED_REASON_VALUES}) is how you tell "the
 * platform turned this off because it kept failing" from "I turned this off" — which decides
 * whether re-enabling requires fixing the receiver first. {@code url} is not updatable: it is
 * half of the endpoint's identity. {@code signingSecretPrefix} identifies which secret it holds;
 * the full secret is shown once, at creation or rotation, and never again.
 */
public record WebhookEndpointResponse(
        String apiVersion,
        Long consecutiveFailureCount,
        String createdAt,
        String description,
        String disabledAt,
        String disabledReason,
        Boolean enabled,
        List<String> enabledEvents,
        String id,
        Map<String, String> metadata,
        Boolean migratedFromLegacy,
        String object,
        String signingSecretPrefix,
        String updatedAt,
        String url) {}
