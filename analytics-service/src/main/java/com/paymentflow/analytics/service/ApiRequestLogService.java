package com.paymentflow.analytics.service;

import com.paymentflow.analytics.domain.ApiRequestLogEntry;
import com.paymentflow.analytics.event.ApiRequestEventPayload;
import com.paymentflow.analytics.repository.ApiRequestLogRepository;
import com.paymentflow.common.dto.event.EventEnvelope;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Records an {@code api.request.events} message as a request-log row (M20.3). */
@Service
public class ApiRequestLogService {

    private static final Logger log = LoggerFactory.getLogger(ApiRequestLogService.class);

    /** Pre-M16 semantics, applied to a null mode exactly as every merchant-scoped table does. */
    private static final String DEFAULT_MODE = "live";

    private final ApiRequestLogRepository repository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public ApiRequestLogService(ApiRequestLogRepository repository, ObjectMapper objectMapper,
                                MeterRegistry meterRegistry) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public void record(EventEnvelope<ApiRequestEventPayload> envelope) {
        ApiRequestEventPayload payload = envelope.payload();
        if (payload == null || payload.merchantId() == null) {
            // The gateway only emits attributable requests (M20.2), so this is a
            // contract violation rather than an expected case — counted, not silently
            // dropped, because a rise here means the producer changed under us.
            meterRegistry.counter("api_request_log_ingest_total", "outcome", "unattributable").increment();
            return;
        }

        Instant occurredAt = envelope.occurredAt() == null ? Instant.now() : envelope.occurredAt();
        String mode = resolveMode(envelope, payload);

        ApiRequestLogEntry entry = new ApiRequestLogEntry(
                UUID.randomUUID(),
                envelope.eventId(),
                payload.merchantId(),
                payload.keyId(),
                mode,
                payload.method(),
                payload.path(),
                payload.queryString(),
                payload.statusCode(),
                payload.durationMs(),
                payload.clientIp(),
                payload.userAgent(),
                payload.correlationId(),
                payload.requestId(),
                payload.errorCode(),
                payload.requestBody(),
                payload.responseBody(),
                writeHeaders(payload.requestHeaders()),
                occurredAt);

        boolean recorded = repository.insertIgnoringDuplicates(entry);
        meterRegistry.counter("api_request_log_ingest_total", "outcome", recorded ? "recorded" : "duplicate")
                .increment();
    }

    /**
     * Mode comes from the envelope, which every M16-aware producer sets, falling back to the
     * payload and then to {@code live} — the same backfill semantics every merchant-scoped
     * table applies to its pre-M16 rows (D101/M16.1).
     */
    private static String resolveMode(EventEnvelope<ApiRequestEventPayload> envelope, ApiRequestEventPayload payload) {
        if (envelope.mode() != null && !envelope.mode().isBlank()) {
            return envelope.mode();
        }
        if (payload.mode() != null && !payload.mode().isBlank()) {
            return payload.mode();
        }
        return DEFAULT_MODE;
    }

    private String writeHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(headers);
        } catch (Exception e) {
            // A header map that will not serialize must not cost us the whole row — the
            // method, path, status and latency are what the log is mostly read for.
            log.warn("Could not serialize request headers for the request log — storing empty", e);
            return "{}";
        }
    }
}
