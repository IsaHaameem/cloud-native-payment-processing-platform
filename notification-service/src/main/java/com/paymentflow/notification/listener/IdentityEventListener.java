package com.paymentflow.notification.listener;

import com.paymentflow.common.dto.event.EventEnvelope;
import com.paymentflow.notification.email.EmailMessage;
import com.paymentflow.notification.email.EmailSender;
import com.paymentflow.notification.event.IdentityNotificationEventPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code identity.events} (M15, task 11) — self-serve-signup-completion
 * emails (verification, password reset). Mirrors {@code NotificationEventListener}'s
 * defensive shape exactly: a malformed message is logged and dropped, not retried
 * (there is no dedup/outbox concern here — unlike payment events, sending a
 * verification email twice for one request is a non-issue, not a correctness bug).
 */
@Component
public class IdentityEventListener {

    private static final Logger log = LoggerFactory.getLogger(IdentityEventListener.class);

    private final EmailSender emailSender;
    private final ObjectMapper objectMapper;

    public IdentityEventListener(EmailSender emailSender, ObjectMapper objectMapper) {
        this.emailSender = emailSender;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${paymentflow.kafka.identity-events-topic}",
            groupId = "${paymentflow.kafka.identity-events-group-id}",
            concurrency = "${paymentflow.kafka.listener-concurrency}")
    public void onMessage(String json) {
        EventEnvelope<IdentityNotificationEventPayload> envelope;
        try {
            envelope = objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructParametricType(EventEnvelope.class, IdentityNotificationEventPayload.class));
        } catch (Exception e) {
            log.error("Could not parse identity event, dropping: {}", json, e);
            return;
        }

        IdentityNotificationEventPayload payload = envelope.payload();
        // Identity events carry no mode (envelope.mode() is null) — recorded verbatim as
        // null, never coerced to live (D126). No merchant context either.
        emailSender.send(new EmailMessage(envelope.eventId(), null, envelope.mode(), payload.recipientEmail(),
                subjectFor(envelope.eventType()), bodyFor(envelope.eventType(), payload), envelope.eventType()));
    }

    private static String subjectFor(String eventType) {
        return switch (eventType) {
            case "EmailVerificationRequested" -> "Verify your email address";
            case "PasswordResetRequested" -> "Reset your password";
            default -> "Account update";
        };
    }

    private static String bodyFor(String eventType, IdentityNotificationEventPayload payload) {
        String action = "EmailVerificationRequested".equals(eventType) ? "verify your email" : "reset your password";
        return "Click the link below to " + action + ": " + payload.actionLink()
                + " (expires " + payload.expiresAt() + ").";
    }
}
