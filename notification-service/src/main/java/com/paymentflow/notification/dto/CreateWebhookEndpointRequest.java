package com.paymentflow.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Registers a webhook endpoint (M18.2). {@code merchantId} and {@code mode} are
 * deliberately absent — both come from the verified {@code MerchantContext} (§7 barrier
 * ①, D28), never from a client-supplied field, so there is no request shape in which a
 * caller could register an endpoint into another merchant's or another mode's partition.
 *
 * <p>{@code enabledEvents} is required and must be non-empty: an endpoint subscribed to
 * nothing is a configuration mistake that looks identical to a broken platform from the
 * merchant's side. {@code "*"} is the way to say "everything", explicitly.
 */
public record CreateWebhookEndpointRequest(

        @NotBlank
        @Size(max = 2048)
        String url,

        @Size(max = 255)
        String description,

        @NotEmpty
        List<@NotBlank @Size(max = 64) String> enabledEvents) {
}
