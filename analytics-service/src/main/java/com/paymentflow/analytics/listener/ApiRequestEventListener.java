package com.paymentflow.analytics.listener;

import com.paymentflow.analytics.event.ApiRequestEventPayload;
import com.paymentflow.analytics.service.ApiRequestLogService;
import com.paymentflow.common.dto.event.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code api.request.events} (M20.3) — analytics-service's second consumer role,
 * following D5's {@code <service>-<topic>} group convention and the same
 * log-and-drop-on-malformed shape as {@link PaymentEventListener}.
 *
 * <p>Given its own listener and its own consumer group rather than being folded into the
 * existing one: this topic has an entirely different volume profile (one message per API
 * request against one per payment event) and its own concurrency, and sharing a group would
 * make request-log backlog delay payment aggregates — two failure domains that have no
 * reason to be coupled.
 */
@Component
public class ApiRequestEventListener {

    private static final Logger log = LoggerFactory.getLogger(ApiRequestEventListener.class);

    private final ApiRequestLogService requestLogService;
    private final ObjectMapper objectMapper;

    public ApiRequestEventListener(ApiRequestLogService requestLogService, ObjectMapper objectMapper) {
        this.requestLogService = requestLogService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${paymentflow.kafka.api-request-events-topic}",
            groupId = "${paymentflow.kafka.api-request-events-group-id}",
            concurrency = "${paymentflow.kafka.api-request-listener-concurrency}")
    public void onMessage(String json) {
        EventEnvelope<ApiRequestEventPayload> envelope;
        try {
            envelope = objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructParametricType(EventEnvelope.class, ApiRequestEventPayload.class));
        } catch (Exception e) {
            log.error("Could not parse api request event, dropping: {}", json, e);
            return;
        }

        requestLogService.record(envelope);
    }
}
