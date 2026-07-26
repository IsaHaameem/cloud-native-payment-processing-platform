package com.paymentflow.notification.mapper;

import com.paymentflow.notification.domain.WebhookEndpoint;
import com.paymentflow.notification.domain.WebhookSubscription;
import com.paymentflow.notification.dto.WebhookEndpointResponse;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Entity → response translation, kept out of the service and the controller alike so
 * neither ever hands a JPA entity to the web layer (the platform's standing rule since
 * V1's {@code MerchantMapper}). Subscriptions are passed in rather than traversed from
 * the endpoint, because {@link WebhookSubscription} deliberately holds no association
 * back to its parent — a lazy one would be an {@code open-in-view: false} failure
 * waiting to happen inside the delivery path.
 */
@Component
public class WebhookEndpointMapper {

    private static final TypeReference<Map<String, String>> METADATA_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public WebhookEndpointMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public WebhookEndpointResponse toResponse(WebhookEndpoint endpoint, List<WebhookSubscription> subscriptions) {
        List<String> enabledEvents = subscriptions.stream()
                .map(WebhookSubscription::getEventType)
                // Sorted so the response is stable across calls — an unordered list would
                // make a merchant's own diff of two responses noisy for no reason.
                .sorted(Comparator.naturalOrder())
                .toList();

        return new WebhookEndpointResponse(
                endpoint.getId(),
                endpoint.getUrl(),
                endpoint.getDescription(),
                endpoint.isEnabled(),
                enabledEvents,
                endpoint.getSigningSecretPrefix(),
                endpoint.getApiVersion(),
                endpoint.getConsecutiveFailureCount(),
                endpoint.getDisabledAt(),
                endpoint.getDisabledReason(),
                endpoint.isMigratedFromLegacy(),
                readMetadata(endpoint.getMetadata()),
                endpoint.getCreatedAt(),
                endpoint.getUpdatedAt());
    }

    /**
     * Serializes merchant-supplied metadata for storage; {@code null} means "not
     * supplied", which the entity turns into {@code {}}. Mirrors payment-service's
     * {@code PaymentMapper.writeMetadata} exactly — the two resources having the same
     * metadata contract is the point of §4.6 listing them together.
     */
    public String writeMetadata(Map<String, String> metadata) {
        return (metadata == null || metadata.isEmpty()) ? null : objectMapper.writeValueAsString(metadata);
    }

    private Map<String, String> readMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(json, METADATA_TYPE);
    }
}
