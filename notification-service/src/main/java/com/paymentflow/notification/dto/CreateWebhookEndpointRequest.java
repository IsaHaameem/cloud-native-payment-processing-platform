package com.paymentflow.notification.dto;

import com.paymentflow.common.openapi.PublicApiParameters;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

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

        @Schema(description = """
                Where to send deliveries. Must be reachable from the public internet over \
                HTTPS: private, link-local and cloud-metadata addresses are refused, and the \
                address is re-checked at delivery time so a hostname cannot be repointed at \
                one afterwards.

                Not updatable later — the URL is half of an endpoint's identity.""",
                example = "https://example.com/webhooks/paymentflow",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 2048)
        String url,

        @Schema(description = "Your own label for this endpoint. Useful once you have more "
                + "than one.", example = "Production order pipeline")
        @Size(max = 255)
        String description,

        @Schema(description = """
                The event types to send here. Must name at least one — an endpoint \
                subscribed to nothing looks identical to a broken platform from your side, \
                so it is refused rather than accepted silently. Use `["*"]` to receive \
                everything, including types added later.""",
                example = "[\"payment.captured\", \"payment.refunded\"]",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty
        List<@NotBlank @Size(max = 64) String> enabledEvents,

        /**
         * Free-form merchant annotation (M19.8, §4.6). Optional; absent and {@code {}}
         * are the same thing. String values only, matching payments and refunds — the
         * platform never interprets, indexes, or filters on it here, and a richer type
         * would imply otherwise.
         */
        @Schema(description = PublicApiParameters.METADATA_FIELD)
        Map<@NotBlank @Size(max = 40) String, @Size(max = 500) String> metadata) {
}
