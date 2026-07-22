package com.paymentflow.common.dto.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EventEnvelopeTest {

    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();

    record SamplePayload(String message) {
    }

    @Test
    void factoryGeneratesIdAndTimestamp() {
        EventEnvelope<SamplePayload> event = EventEnvelope.of(
                "SampleCreated", "agg-1", "corr-1", new SamplePayload("hello"));

        assertThat(event.eventId()).isNotNull();
        assertThat(event.occurredAt()).isNotNull();
        assertThat(event.eventType()).isEqualTo("SampleCreated");
        assertThat(event.aggregateId()).isEqualTo("agg-1");
        assertThat(event.payload().message()).isEqualTo("hello");
    }

    @Test
    void twoEventsGetDistinctIds() {
        EventEnvelope<SamplePayload> first = EventEnvelope.of("X", "a", "c", new SamplePayload("1"));
        EventEnvelope<SamplePayload> second = EventEnvelope.of("X", "a", "c", new SamplePayload("2"));

        assertThat(first.eventId()).isNotEqualTo(second.eventId());
    }

    @Test
    void roundTripsThroughJacksonPreservingPayloadType() throws Exception {
        EventEnvelope<SamplePayload> original = EventEnvelope.of(
                "SampleCreated", "agg-1", "corr-1", new SamplePayload("hello"));

        String json = mapper.writeValueAsString(original);
        assertThat(json).contains("\"eventType\":\"SampleCreated\"").contains("\"message\":\"hello\"");

        EventEnvelope<SamplePayload> parsed = mapper.readValue(json,
                mapper.getTypeFactory().constructParametricType(EventEnvelope.class, SamplePayload.class));

        assertThat(parsed.payload().message()).isEqualTo("hello");
        assertThat(parsed.eventId()).isEqualTo(original.eventId());
    }

    @Test
    void omitsNullCorrelationId() throws Exception {
        EventEnvelope<SamplePayload> event = EventEnvelope.of("X", "a", null, new SamplePayload("hi"));

        assertThat(mapper.writeValueAsString(event)).doesNotContain("correlationId");
    }

    @Test
    void modeFactoryCarriesModeOnTheEnvelopeAndWire() throws Exception {
        EventEnvelope<SamplePayload> event = EventEnvelope.of(
                "SampleCreated", "agg-1", "corr-1", "test", new SamplePayload("hello"));

        assertThat(event.mode()).isEqualTo("test");
        assertThat(mapper.writeValueAsString(event)).contains("\"mode\":\"test\"");
    }

    @Test
    void modelessFactoryLeavesModeNullAndOmitsItFromTheWire() throws Exception {
        // The pre-M16 wire form must be byte-identical: a null mode is dropped by NON_NULL,
        // so a mode-unaware producer's output is indistinguishable from before this field existed.
        EventEnvelope<SamplePayload> event = EventEnvelope.of("X", "a", "c", new SamplePayload("hi"));

        assertThat(event.mode()).isNull();
        assertThat(mapper.writeValueAsString(event)).doesNotContain("mode");
    }

    @Test
    void modelessConstructorLeavesModeNull() {
        // The backward-compatible 6-arg constructor is what pre-M16 consumer tests still call.
        EventEnvelope<SamplePayload> event = new EventEnvelope<>(
                UUID.randomUUID(), "X", "a", Instant.now(), "c", new SamplePayload("hi"));

        assertThat(event.mode()).isNull();
    }

    @Test
    void deserializesLegacyJsonWithoutModeAsNull() throws Exception {
        // A message published before mode existed (or by a not-yet-migrated producer) has
        // no "mode" property; it must deserialize cleanly with mode == null, which every
        // consumer then interprets as "live".
        String legacyJson = """
                {"eventId":"%s","eventType":"SampleCreated","aggregateId":"agg-1",\
                "occurredAt":"2026-07-22T00:00:00Z","correlationId":"corr-1","payload":{"message":"hello"}}\
                """.formatted(UUID.randomUUID());

        EventEnvelope<SamplePayload> parsed = mapper.readValue(legacyJson,
                mapper.getTypeFactory().constructParametricType(EventEnvelope.class, SamplePayload.class));

        assertThat(parsed.mode()).isNull();
        assertThat(parsed.payload().message()).isEqualTo("hello");
    }

    @Test
    void roundTripsModeWhenPresent() throws Exception {
        EventEnvelope<SamplePayload> original = EventEnvelope.of(
                "SampleCreated", "agg-1", "corr-1", "live", new SamplePayload("hello"));

        String json = mapper.writeValueAsString(original);
        EventEnvelope<SamplePayload> parsed = mapper.readValue(json,
                mapper.getTypeFactory().constructParametricType(EventEnvelope.class, SamplePayload.class));

        assertThat(parsed.mode()).isEqualTo("live");
        assertThat(parsed.payload().message()).isEqualTo("hello");
    }
}
