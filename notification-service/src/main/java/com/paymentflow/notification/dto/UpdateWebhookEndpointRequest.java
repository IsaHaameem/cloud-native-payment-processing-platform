package com.paymentflow.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * Partial update of an endpoint (M18.2). Every field is nullable and {@code null} means
 * "leave unchanged" — a PATCH, not a PUT, so a client that only wants to re-enable an
 * endpoint does not have to re-send its subscription list and risk truncating it.
 *
 * <p>The URL is deliberately not updatable: it is half of the endpoint's identity
 * ({@code uq_webhook_endpoints_merchant_mode_url}), and silently repointing an existing
 * endpoint would keep its delivery history attached to a destination that never received
 * any of it. Register a new endpoint and delete the old one instead.
 */
public record UpdateWebhookEndpointRequest(

        @Size(max = 255)
        String description,

        Boolean enabled,

        List<@NotBlank @Size(max = 64) String> enabledEvents,

        /**
         * {@code null} leaves metadata unchanged, like every other field here. A supplied
         * map replaces the stored one wholesale rather than merging — sending {@code {}}
         * is how a merchant clears it, and a merge would leave no way to remove a key.
         */
        Map<@NotBlank @Size(max = 40) String, @Size(max = 500) String> metadata) {
}
