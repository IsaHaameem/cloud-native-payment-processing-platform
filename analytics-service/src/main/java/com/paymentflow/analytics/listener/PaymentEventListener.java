package com.paymentflow.analytics.listener;

import com.paymentflow.analytics.event.AnalyticsEventPayload;
import com.paymentflow.analytics.service.AnalyticsService;
import com.paymentflow.common.dto.event.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code payment.events} (D5's convention: consumer group
 * {@code <service>-<topic>}). Mirrors transaction-service's/notification-service's
 * identical listener shape (M6/M7): malformed messages are logged and dropped, not
 * retried.
 */
@Component
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    private final AnalyticsService analyticsService;
    private final ObjectMapper objectMapper;

    public PaymentEventListener(AnalyticsService analyticsService, ObjectMapper objectMapper) {
        this.analyticsService = analyticsService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${paymentflow.kafka.payment-events-topic}",
            concurrency = "${paymentflow.kafka.listener-concurrency}")
    public void onMessage(String json) {
        EventEnvelope<AnalyticsEventPayload> envelope;
        try {
            envelope = objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructParametricType(EventEnvelope.class, AnalyticsEventPayload.class));
        } catch (Exception e) {
            log.error("Could not parse payment event, dropping: {}", json, e);
            return;
        }

        analyticsService.processEvent(envelope);
    }
}
