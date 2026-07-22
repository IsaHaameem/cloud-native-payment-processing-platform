package com.paymentflow.notification.email;

import java.util.UUID;

/**
 * A single email to (simulated-)send (M15, Decision 2). {@code merchantId} is
 * nullable — populated for payment-lifecycle emails (D45), absent for identity-driven
 * emails (verification/reset), which have no merchant context at all.
 *
 * <p>{@code mode} (M16) is the test/live partition the source event declared, recorded
 * verbatim: {@code test}/{@code live} for a payment-lifecycle email, {@code null} for an
 * identity-driven one (which has no mode at all) — audit's recorder semantics (D126),
 * never coerced to live.
 */
public record EmailMessage(
        UUID eventId,
        UUID merchantId,
        String mode,
        String recipientEmail,
        String subject,
        String body,
        String eventType) {
}
