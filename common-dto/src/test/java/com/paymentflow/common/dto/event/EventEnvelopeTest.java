package com.paymentflow.common.dto.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

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
}
