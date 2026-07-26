package com.paymentflow.notification.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.paymentflow.notification.domain.WebhookEvent;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * The wire form of a canonical event (M18.3, §4.5) — what is HMAC-signed and POSTed to
 * an endpoint, and what M19's Events API returns for the same event. Assembled from a
 * {@link WebhookEvent} row rather than stored: the row's own columns <em>are</em> the
 * envelope ({@code eventRef}, {@code eventType}, {@code apiVersion}, {@code mode},
 * {@code occurredAt}), and only the resource snapshot lives in its {@code data} column.
 * Storing the assembled envelope too would duplicate every one of those fields in two
 * places that could disagree.
 *
 * <p>{@code data} is an object wrapping {@code object} rather than the resource
 * directly. The indirection looks redundant today and is not: it is the seam that lets a
 * later revision add siblings — {@code previousAttributes} being the obvious one —
 * without changing the type of {@code data}, which would be a breaking change under
 * §4.10.
 *
 * <p>The resource is carried as a parsed {@link JsonNode} rather than a typed record
 * because it is already stored as {@code jsonb} and this class is deliberately agnostic
 * about which resource it wraps — {@code refund} objects arrive in M19 and must need no
 * change here.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WebhookEventBody(
        String id,
        String object,
        String type,
        String apiVersion,
        Instant created,
        String mode,
        Data data) {

    /** The discriminator value carried by every event envelope. */
    public static final String OBJECT_TYPE = "event";

    /** The {@code data} wrapper — see the class javadoc for why the extra level exists. */
    public record Data(JsonNode object) {
    }

    public static WebhookEventBody from(WebhookEvent event, JsonNode dataObject) {
        return new WebhookEventBody(
                event.getEventRef(),
                OBJECT_TYPE,
                event.getEventType(),
                event.getApiVersion(),
                event.getOccurredAt(),
                event.getMode(),
                new Data(dataObject));
    }
}
