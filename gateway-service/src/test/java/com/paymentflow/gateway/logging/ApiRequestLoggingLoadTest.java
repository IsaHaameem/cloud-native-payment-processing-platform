package com.paymentflow.gateway.logging;

import com.paymentflow.gateway.config.RequestLoggingProperties;
import com.paymentflow.gateway.security.apikey.ApiKeyAuthenticationWebFilter;
import com.paymentflow.gateway.security.apikey.ApiKeyVerifyResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * M20.8 — the load proof §5/M20's testing strategy singles out: "confirm the logging path adds
 * negligible latency and that a deliberately stalled Kafka producer causes dropped log events,
 * <b>not</b> failed or slowed requests. This is the property most worth proving by experiment
 * rather than by reading the code."
 *
 * <p>M20.2's unit test showed the buffer drops when full. This one shows the property that
 * actually matters in production: <b>under sustained concurrent load with the broker wedged, the
 * request path stays fast and nothing fails.</b> Those are different claims — a design can drop
 * correctly and still serialize every caller on a lock while doing it.
 *
 * <p>The producer is stalled rather than merely slow, because that is the worst realistic case:
 * a broker that accepts the connection and then never responds, which is exactly what
 * {@code max.block.ms} exists for and what the CI investigation found notification-service
 * getting wrong.
 */
class ApiRequestLoggingLoadTest {

    private static final int THREADS = 16;
    private static final int REQUESTS_PER_THREAD = 250;
    private static final int TOTAL_REQUESTS = THREADS * REQUESTS_PER_THREAD;

    /**
     * The budget the logging filter is allowed to add per request while the broker is wedged.
     * Deliberately generous compared with the sub-millisecond cost expected — the assertion
     * exists to catch a *blocking* regression (a lock, a synchronous send, an unbounded queue
     * growing into GC pressure), which shows up as tens of milliseconds or more, not as noise.
     */
    private static final long MAX_MEAN_MICROS = 5_000;
    private static final long MAX_P99_MICROS = 50_000;

    private static ApiKeyVerifyResult context() {
        return new ApiKeyVerifyResult(UUID.randomUUID(), UUID.randomUUID(), "test",
                List.of("payments:read"), "dev@example.com", null);
    }

    private static ServerWebExchange exchange() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/payments").header("User-Agent", "load/1").build());
        exchange.getAttributes().put(ApiKeyAuthenticationWebFilter.RESOLVED_KEY_CONTEXT_ATTRIBUTE, context());
        return exchange;
    }

    @Test
    @DisplayName("with Kafka wedged, 4000 concurrent requests all succeed, stay fast, and drop log events")
    @SuppressWarnings("unchecked")
    void aStalledProducerDropsEventsWithoutFailingOrSlowingRequests() throws Exception {
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        CountDownLatch releaseProducer = new CountDownLatch(1);

        // A broker that never answers: send() parks until the test releases it. This is what a
        // partitioned or overloaded Kafka looks like from the client's side.
        KafkaTemplate<String, String> wedged = mock(KafkaTemplate.class);
        doAnswer(invocation -> {
            releaseProducer.await(60, TimeUnit.SECONDS);
            return null;
        }).when(wedged).send(anyString(), anyString(), anyString());

        RequestLoggingProperties properties = new RequestLoggingProperties(true, 1000, 4096, true);
        ApiRequestEventPublisher publisher =
                new ApiRequestEventPublisher(wedged, new ObjectMapper(), meterRegistry, properties);
        ApiRequestLoggingFilter filter = new ApiRequestLoggingFilter(publisher, properties);

        long[] latenciesMicros = new long[TOTAL_REQUESTS];
        AtomicInteger completed = new AtomicInteger();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (int t = 0; t < THREADS; t++) {
                final int threadIndex = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < REQUESTS_PER_THREAD; i++) {
                            long began = System.nanoTime();
                            try {
                                filter.filter(exchange(), ex -> {
                                    ex.getResponse().setStatusCode(HttpStatus.OK);
                                    return Mono.empty();
                                }).block();
                            } catch (Throwable failure) {
                                firstFailure.compareAndSet(null, failure);
                            }
                            latenciesMicros[threadIndex * REQUESTS_PER_THREAD + i] =
                                    (System.nanoTime() - began) / 1_000;
                            completed.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(120, TimeUnit.SECONDS))
                    .as("every request must complete while the broker is wedged")
                    .isTrue();
        } finally {
            releaseProducer.countDown();
            pool.shutdownNow();
        }

        // 1. Nothing failed. A logging pipeline that can fail a customer request is worse than
        //    no logging at all (D109).
        assertThat(firstFailure.get()).as("no request may fail because logging is stalled").isNull();
        assertThat(completed.get()).isEqualTo(TOTAL_REQUESTS);

        // 2. Nothing was slowed. This is the assertion a lock or a synchronous send would break.
        java.util.Arrays.sort(latenciesMicros);
        long mean = (long) java.util.Arrays.stream(latenciesMicros).average().orElse(0);
        long p99 = latenciesMicros[(int) (TOTAL_REQUESTS * 0.99)];
        long max = latenciesMicros[TOTAL_REQUESTS - 1];

        assertThat(mean).as("mean added latency was %dus (budget %dus)", mean, MAX_MEAN_MICROS)
                .isLessThan(MAX_MEAN_MICROS);
        assertThat(p99).as("p99 added latency was %dus (budget %dus); max %dus", p99, MAX_P99_MICROS, max)
                .isLessThan(MAX_P99_MICROS);

        // 3. Events were dropped, and counted. The degradation is visible rather than silent.
        double dropped = meterRegistry.counter("api_request_log_events_total", "outcome", "dropped").count();
        assertThat(dropped)
                .as("a 1000-deep buffer against %d requests with a wedged producer must overflow", TOTAL_REQUESTS)
                .isPositive();
        assertThat(dropped).isLessThan(TOTAL_REQUESTS);

        System.out.printf("M20.8 load proof: %d requests, mean %dus, p99 %dus, max %dus, %.0f events dropped%n",
                TOTAL_REQUESTS, mean, p99, max, dropped);
    }
}
