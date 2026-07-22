package com.paymentflow.notification.email;

import com.paymentflow.notification.domain.EmailLogEntry;
import com.paymentflow.notification.repository.EmailLogEntryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The platform's only {@link EmailSender} today — no real SMTP/SES integration exists
 * in this milestone (D45, unchanged). "Sending" an email means durably logging it via
 * {@code email_log}, exactly as before M15; only the seam around this behavior is new
 * (Decision 2).
 */
@Component
public class SimulatedEmailSender implements EmailSender {

    private final EmailLogEntryRepository emailLogEntryRepository;
    private final MeterRegistry meterRegistry;

    public SimulatedEmailSender(EmailLogEntryRepository emailLogEntryRepository, MeterRegistry meterRegistry) {
        this.emailLogEntryRepository = emailLogEntryRepository;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void send(EmailMessage message) {
        emailLogEntryRepository.save(EmailLogEntry.of(message.eventId(), message.merchantId(), message.mode(),
                message.recipientEmail(), message.subject(), message.body()));
        meterRegistry.counter("email_logged_total", "eventType", message.eventType()).increment();
    }
}
