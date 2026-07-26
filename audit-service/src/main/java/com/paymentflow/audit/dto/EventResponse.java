package com.paymentflow.audit.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * The merchant-facing event (M19.5, §5/M19 task 4) — the same canonical {@code evt_}
 * shape M18 defined for a webhook body, served here from {@code audit_log}.
 *
 * <p>Identical by construction rather than by agreement: the {@code evt_} id is derived
 * from the envelope id by {@code CanonicalEventType.eventRefFor}, and the type name comes
 * from the same shared enum notification-service uses. That is why the vocabulary was
 * promoted to {@code common-dto} in this sub-milestone — two hand-maintained copies of a
 * frozen public shape is precisely the drift §10/R10 warns about.
 *
 * <p>{@code data} is the stored payload, projected rather than re-derived. audit-service
 * records events verbatim as an opaque tree (D44) and has no business knowing what a
 * payment looks like, so it returns what it was given rather than inventing a typed view
 * it would then have to keep in step with payment-service.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventResponse(
        String id,
        String object,
        String type,
        String mode,
        Instant created,
        JsonNode data) {

    /** The discriminator carried by every event object. */
    public static final String OBJECT_TYPE = "event";
}
