package com.paymentflow.common.dto.event;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Standard envelope for every domain event this platform publishes to Kafka
 * (D14/D1). Framework-free, like {@link com.paymentflow.common.dto.error.ApiError} —
 * common-dto stays a pure data-contract module.
 *
 * <p>Deliberately does <b>not</b> carry the event payload's Java type across service
 * boundaries: each producer instantiates {@code EventEnvelope<ItsOwnPayloadType>} and
 * serializes the whole thing to JSON; each consumer deserializes into its own locally
 * defined payload shape for that event type. This mirrors schema-per-service (D4) —
 * services share a message *shape*, never a Java *class*, so no service's internal
 * model leaks into another's compile-time dependencies.
 *
 * <p>{@code eventId} is the de-duplication key at-least-once consumers (D2) must use
 * to make their own processing idempotent — the platform never assumes exactly-once
 * delivery.
 *
 * <p>{@code mode} (M16) is the test/live data-plane partition the event belongs to
 * (§4.4) — {@code "test"} or {@code "live"}, matching the raw-key mode segment. It is
 * nullable and, being {@code NON_NULL}, omitted from the wire form entirely when a
 * producer hasn't set it (or on an in-flight pre-M16 message); a consumer reading a
 * {@code null} mode treats it as {@code "live"} — the same backfill semantics every
 * merchant-scoped table applies to its pre-M16 rows. Carried on the envelope rather
 * than inside each producer's payload, so every consumer receives it without a lookup
 * and cannot accidentally cross-post between modes.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        String aggregateId,
        Instant occurredAt,
        String correlationId,
        String mode,
        T payload) {

    /**
     * Backward-compatible constructor for callers that don't carry a mode: mode is left
     * {@code null} and, being {@code NON_NULL}, omitted from the serialized JSON, so the
     * wire form is byte-identical to the pre-M16 envelope. Retained so pre-M16 producers
     * and consumer tests compile unchanged while services are migrated to mode-awareness
     * one at a time (M16.1 adds the field; M16.2+ start populating and reading it).
     */
    public EventEnvelope(UUID eventId, String eventType, String aggregateId, Instant occurredAt,
                         String correlationId, T payload) {
        this(eventId, eventType, aggregateId, occurredAt, correlationId, null, payload);
    }

    /** Backward-compatible factory (no mode) — see the mode-less constructor above. */
    public static <T> EventEnvelope<T> of(String eventType, String aggregateId, String correlationId, T payload) {
        return new EventEnvelope<>(
                UUID.randomUUID(), eventType, aggregateId, Instant.now(), correlationId, null, payload);
    }

    /** M16 mode-carrying factory: the form producers use once they resolve a request's mode. */
    public static <T> EventEnvelope<T> of(String eventType, String aggregateId, String correlationId,
                                          String mode, T payload) {
        return new EventEnvelope<>(
                UUID.randomUUID(), eventType, aggregateId, Instant.now(), correlationId, mode, payload);
    }
}
