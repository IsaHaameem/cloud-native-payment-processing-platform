package com.paymentflow.notification.mapper;

import com.paymentflow.notification.domain.WebhookDelivery;
import com.paymentflow.notification.domain.WebhookDeliveryAttempt;
import com.paymentflow.notification.domain.WebhookEvent;
import com.paymentflow.notification.dto.WebhookDeliveryAttemptResponse;
import com.paymentflow.notification.dto.WebhookDeliveryResponse;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Delivery + attempts → the delivery-log response (M18.8). The canonical event is passed
 * in rather than looked up here so the list view can resolve a page of events in one
 * query; a mapper that fetched its own dependencies would reintroduce the N+1 the query
 * service exists to avoid.
 */
@Component
public class WebhookDeliveryMapper {

    public WebhookDeliveryResponse toResponse(WebhookDelivery delivery, WebhookEvent event,
                                              List<WebhookDeliveryAttempt> attempts) {
        return new WebhookDeliveryResponse(
                delivery.getId(),
                WebhookDeliveryResponse.OBJECT_TYPE,
                event == null ? null : event.getEventRef(),
                event == null ? null : event.getEventType(),
                delivery.getEndpointId(),
                delivery.getWebhookUrl(),
                delivery.getStatus(),
                delivery.getAttemptCount(),
                delivery.getNextAttemptAt(),
                delivery.getReplayedFromDeliveryId(),
                delivery.getCreatedAt(),
                delivery.getLastAttemptedAt(),
                attempts.stream().map(WebhookDeliveryMapper::toResponse).toList());
    }

    private static WebhookDeliveryAttemptResponse toResponse(WebhookDeliveryAttempt attempt) {
        return new WebhookDeliveryAttemptResponse(
                attempt.getId(),
                attempt.getAttemptNumber(),
                attempt.getOutcome(),
                attempt.getRequestUrl(),
                attempt.getRequestHeaders(),
                attempt.getRequestBody(),
                attempt.getResponseStatus(),
                attempt.getResponseHeaders(),
                attempt.getResponseBody(),
                attempt.getDurationMs(),
                attempt.getError(),
                attempt.getAttemptedAt());
    }
}
