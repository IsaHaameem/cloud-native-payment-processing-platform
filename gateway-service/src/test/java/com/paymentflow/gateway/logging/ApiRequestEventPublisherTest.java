package com.paymentflow.gateway.logging;

import com.paymentflow.gateway.config.RequestLoggingProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

/**
 * M20.2. The class exists for one property — <b>drop rather than block</b> (D109) — so that
 * is what these tests hold down, rather than the happy path alone.
 */
class ApiRequestEventPublisherTest {

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static ApiRequestEventPayload payload() {
        return new ApiRequestEventPayload(UUID.randomUUID(), UUID.randomUUID(), "test", "GET", "/v1/payments",
                null, 200, 12, "127.0.0.1", "curl/8", "corr", "req", null, null, null, null);
    }

    private ApiRequestEventPublisher publisherWith(KafkaTemplate<String, String> template, int capacity) {
        return new ApiRequestEventPublisher(template, objectMapper, meterRegistry,
                new RequestLoggingProperties(true, capacity, 4096, true));
    }

    private double counter(String outcome) {
        return meterRegistry.counter("api_request_log_events_total", "outcome", outcome).count();
    }

    @Test
    @DisplayName("an event is published to the topic, keyed by merchant")
    @SuppressWarnings("unchecked")
    void publishesToKafka() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        ApiRequestEventPublisher publisher = publisherWith(template, 100);
        ApiRequestEventPayload event = payload();

        assertThat(publisher.publish(event)).isTrue();

        Awaitility.await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> verify(template).send(
                        org.mockito.ArgumentMatchers.eq(ApiRequestEventPublisher.TOPIC),
                        org.mockito.ArgumentMatchers.eq(event.merchantId().toString()),
                        anyString()));
        assertThat(counter("published")).isEqualTo(1);
    }

    @Test
    @DisplayName("a stalled Kafka producer causes dropped events, never a blocked caller")
    @SuppressWarnings("unchecked")
    void dropsRatherThanBlocksWhenTheProducerStalls() throws Exception {
        // The completion criterion, in unit form: hold the drain thread inside send() so the
        // bounded queue fills, then assert that publish() still returns immediately and
        // simply reports the drop. M20.8 proves the same property under real load.
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger sendAttempts = new AtomicInteger();
        KafkaTemplate<String, String> stalled = mock(KafkaTemplate.class);
        doAnswer(invocation -> {
            sendAttempts.incrementAndGet();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(stalled).send(anyString(), anyString(), anyString());

        int capacity = 4;
        ApiRequestEventPublisher publisher = publisherWith(stalled, capacity);

        // Wait until the drain thread is definitively parked inside send(), so what follows
        // measures a full buffer rather than a race with the drainer.
        publisher.publish(payload());
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> sendAttempts.get() >= 1);

        long startNanos = System.nanoTime();
        int accepted = 0;
        int dropped = 0;
        for (int i = 0; i < capacity + 50; i++) {
            if (publisher.publish(payload())) {
                accepted++;
            } else {
                dropped++;
            }
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(dropped).as("the buffer is bounded, so the excess must be dropped").isPositive();
        assertThat(accepted).as("the buffer accepts up to its capacity").isLessThanOrEqualTo(capacity);
        assertThat(elapsedMs)
                .as("publish() must never wait on the stalled producer — 54 calls took %dms", elapsedMs)
                .isLessThan(1000);
        assertThat(counter("dropped")).isEqualTo(dropped);

        release.countDown();
    }

    @Test
    @DisplayName("a Kafka failure is counted, never thrown at the caller")
    @SuppressWarnings("unchecked")
    void countsSendFailuresWithoutPropagating() {
        KafkaTemplate<String, String> failing = mock(KafkaTemplate.class);
        doAnswer(invocation -> {
            throw new IllegalStateException("broker unreachable");
        }).when(failing).send(anyString(), anyString(), anyString());

        ApiRequestEventPublisher publisher = publisherWith(failing, 10);
        assertThat(publisher.publish(payload())).isTrue();

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(counter("failed")).isEqualTo(1));
    }

    @Test
    @DisplayName("the drain thread survives a failure and keeps publishing")
    @SuppressWarnings("unchecked")
    void drainThreadSurvivesFailures() {
        // A drain thread that dies takes the entire request log with it, silently, for the
        // lifetime of the process — so one poisoned event must not end the loop.
        AtomicInteger calls = new AtomicInteger();
        KafkaTemplate<String, String> flaky = mock(KafkaTemplate.class);
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("transient");
            }
            return null;
        }).when(flaky).send(anyString(), anyString(), anyString());

        ApiRequestEventPublisher publisher = publisherWith(flaky, 10);
        publisher.publish(payload());
        publisher.publish(payload());
        publisher.publish(payload());

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            verify(flaky, times(3)).send(anyString(), anyString(), anyString());
            assertThat(counter("published")).isEqualTo(2);
            assertThat(counter("failed")).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("an unattributed event still keys deterministically rather than crashing")
    @SuppressWarnings("unchecked")
    void handlesANullMerchantId() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        ApiRequestEventPublisher publisher = publisherWith(template, 10);

        publisher.publish(new ApiRequestEventPayload(null, null, null, "GET", "/v1/payments",
                null, 401, 3, "127.0.0.1", null, "corr", "req", "UNAUTHORIZED", null, null, null));

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                verify(template).send(anyString(), org.mockito.ArgumentMatchers.eq("unattributed"), anyString()));
    }
}
