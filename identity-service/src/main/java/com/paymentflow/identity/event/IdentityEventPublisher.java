package com.paymentflow.identity.event;

import com.paymentflow.common.correlation.CorrelationConstants;
import com.paymentflow.common.dto.event.EventEnvelope;
import com.paymentflow.identity.config.ActionLinkProperties;
import com.paymentflow.identity.domain.OutboxEvent;
import com.paymentflow.identity.domain.User;
import com.paymentflow.identity.repository.OutboxEventRepository;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Writes an outbox row for a self-serve-signup-completion event (M15, task 11) —
 * mirrors {@code PaymentEventPublisher}/merchant-service's {@code MerchantEventPublisher}
 * exactly (D3): callers invoke this from within the same transaction as the token
 * they just issued; this class only ever appends to {@code outbox_events}.
 */
@Component
public class IdentityEventPublisher {

    public static final String TOPIC = "identity.events";

    private final OutboxEventRepository outboxEventRepository;
    private final ActionLinkProperties actionLinkProperties;
    private final ObjectMapper objectMapper;

    public IdentityEventPublisher(OutboxEventRepository outboxEventRepository,
                                  ActionLinkProperties actionLinkProperties, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.actionLinkProperties = actionLinkProperties;
        this.objectMapper = objectMapper;
    }

    public void publishEmailVerificationRequested(User user, String rawToken, Instant expiresAt) {
        publish(user, "EmailVerificationRequested", "/verify-email", rawToken, expiresAt);
    }

    public void publishPasswordResetRequested(User user, String rawToken, Instant expiresAt) {
        publish(user, "PasswordResetRequested", "/reset-password", rawToken, expiresAt);
    }

    private void publish(User user, String eventType, String path, String rawToken, Instant expiresAt) {
        String link = actionLinkProperties.baseUrl() + path + "?token="
                + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
        IdentityEventPayload payload =
                new IdentityEventPayload(user.getId(), user.getEmail(), link, expiresAt.toString());

        String correlationId = MDC.get(CorrelationConstants.CORRELATION_ID_MDC_KEY);
        EventEnvelope<IdentityEventPayload> envelope =
                EventEnvelope.of(eventType, user.getId().toString(), correlationId, payload);

        String json = objectMapper.writeValueAsString(envelope);
        outboxEventRepository.save(OutboxEvent.of(user.getId(), eventType, TOPIC, json));
    }
}
