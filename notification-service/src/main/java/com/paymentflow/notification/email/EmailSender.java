package com.paymentflow.notification.email;

/**
 * The seam behind which the platform's email transport lives (M15, Decision 2).
 * {@link SimulatedEmailSender} is the only implementation today (D45, unchanged
 * behavior) — a future real provider (SES) implements this same interface with zero
 * change to any caller's business logic.
 */
public interface EmailSender {

    void send(EmailMessage message);
}
