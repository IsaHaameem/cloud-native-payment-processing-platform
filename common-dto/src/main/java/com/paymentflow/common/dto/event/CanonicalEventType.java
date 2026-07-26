package com.paymentflow.common.dto.event;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The canonical, merchant-facing event vocabulary — the public names that appear in a
 * webhook body, in M19's Events API, and in every SDK from M22 onward.
 *
 * <p><b>Lives in {@code common-dto} because two services now need it (M19.5).</b> M18
 * defined it inside notification-service, correctly, when that was the only service
 * producing merchant-facing events. M19's Events API is served by audit-service from its
 * own {@code audit_log}, and D4 forbids one service importing another's internal types —
 * so the choice was to duplicate the vocabulary or promote it. It is promoted, because
 * this is a *public contract* rather than an internal model, and public contracts are
 * exactly what {@code common-dto} exists for ({@code ApiError}, {@code PageResponse},
 * {@code EventEnvelope} are all here for the same reason). Two copies of a frozen public
 * vocabulary drifting apart is the failure this prevents.
 *
 * <p>Deliberately <em>not</em> the platform's internal event-type strings
 * ({@code PaymentAuthorized}, …): those are payment-service's own vocabulary and never a
 * public promise. The mapping between them lives here so it, too, exists once.
 *
 * <p>Naming is {@code <resource>.<past-tense verb>} in lower snake_case — it reads
 * correctly in a subscription list and leaves obvious room for resources added later
 * ({@code refund.*}, and disputes if §13-Q7 is ever answered yes) without renaming
 * anything that already exists.
 *
 * <p><b>Adding a value is additive and never breaking</b> (§4.10): a wildcard-subscribed
 * endpoint starts receiving it and every other endpoint is unaffected. Clients must
 * tolerate unknown event types.
 */
public enum CanonicalEventType {

    PAYMENT_CREATED("payment.created", "PaymentCreated"),
    PAYMENT_AUTHORIZED("payment.authorized", "PaymentAuthorized"),
    PAYMENT_CAPTURED("payment.captured", "PaymentCaptured"),
    PAYMENT_FAILED("payment.failed", "PaymentFailed"),
    PAYMENT_VOIDED("payment.voided", "PaymentVoided"),
    PAYMENT_REFUNDED("payment.refunded", "PaymentRefunded"),
    PAYMENT_PARTIALLY_REFUNDED("payment.partially_refunded", "PaymentPartiallyRefunded");

    /** The public identifier prefix for an event, matching the {@code pk_}/{@code sk_}/{@code whsec_} convention. */
    public static final String ID_PREFIX = "evt_";

    private final String canonicalName;
    private final String internalEventType;

    CanonicalEventType(String canonicalName, String internalEventType) {
        this.canonicalName = canonicalName;
        this.internalEventType = internalEventType;
    }

    /** The public name, e.g. {@code payment.authorized}. */
    public String canonicalName() {
        return canonicalName;
    }

    /** The producer's internal {@code EventEnvelope.eventType}, e.g. {@code PaymentAuthorized}. */
    public String internalEventType() {
        return internalEventType;
    }

    /**
     * Translates an internal event type to its canonical form. Empty for an internal
     * event the platform deliberately does not surface to merchants — {@code merchant.events}'
     * key lifecycle, for instance, is audit's concern but not a webhook and not a
     * merchant-facing event. Callers treat empty as "not merchant-facing", never as an
     * error, so a new internal event type cannot break a consumer.
     */
    public static Optional<CanonicalEventType> fromInternal(String internalEventType) {
        return Arrays.stream(values())
                .filter(type -> type.internalEventType.equals(internalEventType))
                .findFirst();
    }

    /** Parses a canonical name exactly — case-sensitively, since these are contract strings, not user input. */
    public static Optional<CanonicalEventType> fromCanonical(String canonicalName) {
        return Arrays.stream(values())
                .filter(type -> type.canonicalName.equals(canonicalName))
                .findFirst();
    }

    public static Set<String> canonicalNames() {
        return Arrays.stream(values()).map(CanonicalEventType::canonicalName).collect(Collectors.toUnmodifiableSet());
    }

    /** The documented vocabulary as a stable, sorted list — used in validation messages and docs. */
    public static String documentedVocabulary() {
        return Arrays.stream(values())
                .map(CanonicalEventType::canonicalName)
                .sorted()
                .collect(Collectors.joining(", "))
                .toLowerCase(Locale.ROOT);
    }

    /**
     * The deterministic public id for an internal event id: {@code "evt_"} followed by the
     * UUID's 32 hex digits.
     *
     * <p>Determinism is the property M19's Events API depends on: audit-service holds the
     * same envelope {@code eventId} that notification-service does, and derives the
     * identical {@code evt_} without consulting the other's schema, sharing a sequence, or
     * coordinating at all. Built into M18.3 for exactly this reason, and promoted here
     * alongside the vocabulary it belongs with.
     */
    public static String eventRefFor(java.util.UUID sourceEventId) {
        return ID_PREFIX + sourceEventId.toString().replace("-", "").toLowerCase(Locale.ROOT);
    }
}
