package com.paymentflow.notification.email;

import java.util.UUID;

/**
 * A single email to (simulated-)send (M15, Decision 2). {@code merchantId} is
 * nullable — populated for payment-lifecycle emails (D45), absent for identity-driven
 * emails (verification/reset), which have no merchant context at all.
 */
public record EmailMessage(
        UUID eventId,
        UUID merchantId,
        String recipientEmail,
        String subject,
        String body,
        String eventType) {
}
