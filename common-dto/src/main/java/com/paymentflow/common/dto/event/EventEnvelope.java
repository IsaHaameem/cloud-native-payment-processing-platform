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
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        String aggregateId,
        Instant occurredAt,
        String correlationId,
        T payload) {

    public static <T> EventEnvelope<T> of(String eventType, String aggregateId, String correlationId, T payload) {
        return new EventEnvelope<>(UUID.randomUUID(), eventType, aggregateId, Instant.now(), correlationId, payload);
    }
}
