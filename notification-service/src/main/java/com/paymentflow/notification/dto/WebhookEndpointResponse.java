package com.paymentflow.notification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.paymentflow.common.openapi.PublicApiParameters;
import com.paymentflow.notification.domain.EndpointDisableReason;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WebhookEndpointResponse(
        @Schema(description = "Unique identifier for this endpoint.")
        UUID id,

        @Schema(description = "Always `webhook_endpoint`.", example = "webhook_endpoint")
        String object,

        @Schema(description = """
                Where deliveries are sent. Not updatable: the URL is half of an endpoint's \
                identity, and repointing one would leave its delivery history attached to a \
                destination that never received any of it. Register a new endpoint instead.""",
                example = "https://example.com/webhooks/paymentflow")
        String url,

        @Schema(description = "Your own label for this endpoint.",
                example = "Production order pipeline")
        String description,

        @Schema(description = "Whether deliveries are currently being sent. May be false "
                + "because you disabled it or because the platform did — see `disabledReason`.")
        boolean enabled,

        @Schema(description = """
                The event types this endpoint receives. `*` means every type, including ones \
                added later.""",
                example = "[\"payment.captured\", \"payment.refunded\"]")
        List<String> enabledEvents,

        @Schema(description = """
                The first few characters of the signing secret, so you can tell which secret \
                an endpoint holds. **The full secret is shown exactly once**, when the \
                endpoint is created or its secret is rotated, and is not retrievable \
                afterwards.""",
                example = "whsec_9f2c")
        String signingSecretPrefix,

        @Schema(description = "The API revision delivery bodies are rendered in for this "
                + "endpoint.", example = "2026-08-01")
        String apiVersion,

        @Schema(description = """
                How many deliveries have failed in a row. Resets to zero on the first \
                success. When it reaches the platform's threshold the endpoint is disabled \
                automatically — a permanently broken receiver is not retried forever.""",
                example = "0")
        int consecutiveFailureCount,

        @Schema(description = "When the endpoint was disabled, if it is.")
        Instant disabledAt,

        @Schema(description = """
                Why the endpoint was disabled. **This is how you tell "the platform turned \
                this off because it kept failing" from "I turned this off"** — which decides \
                whether re-enabling it requires fixing something first. Absent on an enabled \
                endpoint.""")
        EndpointDisableReason disabledReason,

        @Schema(description = "Whether this endpoint was carried over from the platform's "
                + "pre-webhook-product configuration.")
        boolean migratedFromLegacy,

        /** Always present, never null — an endpoint with no metadata reports {@code {}} (M19.8). */
        @Schema(description = PublicApiParameters.METADATA_FIELD)
        Map<String, String> metadata,

        @Schema(description = "When the endpoint was registered, as RFC 3339.")
        Instant createdAt,

        @Schema(description = "When the endpoint last changed, as RFC 3339.")
        Instant updatedAt) {

    /** The discriminator carried by every webhook-endpoint object. */
    public static final String OBJECT_TYPE = "webhook_endpoint";
}
