package com.paymentflow.audit.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
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
        @Schema(description = """
                Unique identifier for this event, `evt_` followed by 32 hex characters. \
                **Byte-identical to the `id` in the webhook body you received for the same \
                event**, so a webhook can be reconciled against this log without storing \
                anything extra.""",
                example = "evt_9f2c1e7a4b8d4c3e8a1d2b4f6a8c05d1")
        String id,

        @Schema(description = "Always `event`. The discriminator that identifies this object "
                + "out of context.", example = "event")
        String object,

        @Schema(description = """
                What happened, from the frozen event vocabulary — `payment.created`, \
                `payment.authorized`, `payment.captured`, `payment.refunded`, \
                `payment.voided`, `payment.failed`. **New types may be added without a new \
                API revision**, so treat an unrecognised type as one you do not handle.""",
                example = "payment.captured")
        String type,

        @Schema(description = "Whether this event describes `test` or `live` activity.",
                allowableValues = {"test", "live"}, example = "test")
        String mode,

        @Schema(description = "When the event occurred — not when it was recorded. Events are "
                + "ordered by this, so a redelivery cannot reorder your feed.")
        Instant created,

        // `type = "object"` is load-bearing, not decoration. Left to reflection, springdoc
        // describes the *Java* JsonNode class and publishes its bean getters — `isArray`,
        // `isBigDecimal`, `getNodeType` — as the schema of the payload, which is a
        // confident, valid-looking description of something no response has ever contained.
        // Found by M21.7's contract tests: the real body validated against none of it.
        //
        // `types` and not `type`, for the reason M21.7 also found on analytics-service's
        // nullable numbers: in a 3.1 document swagger honours `types` and silently loses
        // `type` — annotated `type = "object"` this field came out as `type: string`, which
        // is the same class of confidently-wrong description with a different value.
        //
        // `implementation = Object.class` is the third of the three attributes and the one
        // that stops the reflection: without it swagger still resolves the field's declared
        // type and emits a `$ref` to the generated JsonNode component *alongside* the
        // declared type, so the schema said `object` and pointed at the bean getters anyway.
        @Schema(implementation = Object.class, types = {"object"},
                additionalProperties = Schema.AdditionalPropertiesValue.TRUE,
                description = """
                The event payload — the object the event happened to, as it was at the time. \
                Its shape depends on `type` and is the same body your webhook endpoint \
                received. Stored verbatim: audit-service records what it was given rather \
                than a typed view it would then have to keep in step.""")
        JsonNode data) {

    /** The discriminator carried by every event object. */
    public static final String OBJECT_TYPE = "event";
}
