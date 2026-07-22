package com.paymentflow.notification.event;

import java.util.UUID;

/**
 * notification-service's own local copy of identity-service's {@code identity.events}
 * payload shape (D36, applied to a second producer besides payment-service). Carries
 * the ready-to-use {@code actionLink} verbatim — notification-service makes no
 * synchronous call back to identity-service to build it.
 */
public record IdentityNotificationEventPayload(
        UUID userId,
        String recipientEmail,
        String actionLink,
        String expiresAt) {
}
