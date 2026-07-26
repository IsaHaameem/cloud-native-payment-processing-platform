package com.paymentflow.gateway.logging;

import com.paymentflow.common.dto.event.EventEnvelope;
import com.paymentflow.gateway.config.RequestLoggingProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ships request-log events to Kafka <b>off</b> the request path, dropping them rather than
 * ever slowing a request down (M20.2, §5/M20 task 1, <b>D109</b>).
 *
 * <p><b>Drop rather than block is the load-bearing property of this class</b>, and every
 * design choice here follows from it. Observability infrastructure that can fail a customer
 * request is worse than no observability, and V1 learned an adjacent version of this in D89,
 * where an OTLP exporter with no receiver spent months quietly retrying on every service.
 *
 * <p>Concretely, the request thread does exactly one thing: {@link BlockingQueue#offer} onto
 * a bounded queue, which is non-blocking and returns {@code false} when full. It never calls
 * Kafka, never waits on a future, never allocates unboundedly, and cannot throw into the
 * filter chain. A single drain thread owns everything that can be slow.
 *
 * <p><b>Why a plain queue and thread rather than a Reactor {@code Sinks} pipeline.</b> The
 * gateway is reactive, so a sink would be the idiomatic-looking choice — but
 * {@code KafkaTemplate} is a blocking-capable API, and putting it on a Reactor scheduler
 * risks parking a thread the event loop shares. An explicitly separate, explicitly bounded,
 * explicitly single-threaded drain has no such coupling: the worst case when Kafka stalls is
 * that the queue fills and events are counted as dropped, which is precisely the degradation
 * M20's completion criteria ask to be demonstrated under load.
 *
 * <p>Drops are counted, never silent. {@code api_request_log_events_total{outcome="dropped"}}
 * is what makes "the log has gaps" a visible operational fact rather than a mystery.
 */
@Component
public class ApiRequestEventPublisher {

    public static final String TOPIC = "api.request.events";
    public static final String EVENT_TYPE = "ApiRequestCompleted";

    private static final Logger log = LoggerFactory.getLogger(ApiRequestEventPublisher.class);
    private static final long DRAIN_POLL_MS = 200;
    private static final long SHUTDOWN_GRACE_SECONDS = 5;

    private final BlockingQueue<ApiRequestEventPayload> queue;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final ExecutorService drainer;
    private final Counter publishedCounter;
    private final Counter droppedCounter;
    private final Counter failedCounter;
    private final AtomicLong droppedSinceLastLog = new AtomicLong();
    private volatile boolean running = true;

    public ApiRequestEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
                                    MeterRegistry meterRegistry, RequestLoggingProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.queue = new ArrayBlockingQueue<>(properties.bufferCapacity());
        this.publishedCounter = meterRegistry.counter("api_request_log_events_total", "outcome", "published");
        this.droppedCounter = meterRegistry.counter("api_request_log_events_total", "outcome", "dropped");
        this.failedCounter = meterRegistry.counter("api_request_log_events_total", "outcome", "failed");
        meterRegistry.gauge("api_request_log_buffer_depth", queue, BlockingQueue::size);

        this.drainer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "api-request-log-drain");
            // Daemon: this thread must never be the reason the JVM refuses to exit. Whatever
            // is still queued at shutdown is a log event, and losing one is the explicitly
            // accepted trade in D109.
            thread.setDaemon(true);
            return thread;
        });
        this.drainer.submit(this::drainLoop);
    }

    /**
     * Hands an event to the buffer. Returns immediately, always — this is the only method the
     * request path calls.
     *
     * @return {@code true} if buffered, {@code false} if dropped because the buffer was full
     */
    public boolean publish(ApiRequestEventPayload payload) {
        if (!running || !queue.offer(payload)) {
            droppedCounter.increment();
            // Rate-limited to one line per 1000 drops: a full buffer means the platform is
            // already under stress, and a log line per dropped event would turn a metrics
            // problem into a disk problem — the exact failure D89 records.
            if (droppedSinceLastLog.incrementAndGet() % 1000 == 1) {
                log.warn("api.request.events buffer full — dropping request-log events (total dropped so far: {})",
                        (long) droppedCounter.count() + 1);
            }
            return false;
        }
        return true;
    }

    private void drainLoop() {
        while (running || !queue.isEmpty()) {
            try {
                ApiRequestEventPayload payload = queue.poll(DRAIN_POLL_MS, TimeUnit.MILLISECONDS);
                if (payload != null) {
                    send(payload);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // A drain thread that dies takes the whole request log with it, silently, for
                // the lifetime of the process. Nothing escapes this loop.
                log.error("Unexpected failure draining the request-log buffer — continuing", e);
            }
        }
    }

    private void send(ApiRequestEventPayload payload) {
        try {
            String aggregateId = payload.merchantId() == null ? "unattributed" : payload.merchantId().toString();
            EventEnvelope<ApiRequestEventPayload> envelope = EventEnvelope.of(
                    EVENT_TYPE, aggregateId, payload.correlationId(), payload.mode(), payload);
            // Keyed by merchant so one merchant's requests stay ordered within a partition
            // and spread across partitions between merchants — the same keying rationale as
            // WebhookDispatcher's endpoint id (M18.6).
            kafkaTemplate.send(TOPIC, aggregateId, objectMapper.writeValueAsString(envelope));
            publishedCounter.increment();
        } catch (Exception e) {
            failedCounter.increment();
            log.warn("Failed to publish an api.request.events message — dropping it", e);
        }
    }

    @PreDestroy
    void shutdown() {
        running = false;
        drainer.shutdown();
        try {
            if (!drainer.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                drainer.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            drainer.shutdownNow();
        }
    }

    /** Buffered-but-not-yet-published depth — exposed for tests and the gauge above. */
    public int bufferedCount() {
        return queue.size();
    }
}
