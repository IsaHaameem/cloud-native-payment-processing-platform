package com.paymentflow.notification.event;

import java.util.UUID;

/**
 * notification-service's own local copy of the JSON shape payment-service publishes
 * (D36, extended to a third consumer). Carries {@code merchantContactEmail}/
 * {@code merchantWebhookUrl} verbatim from the event (D43) — notification-service makes
 * no synchronous call back to merchant-service to learn where to deliver.
 */
public record PaymentNotificationEventPayload(
        UUID paymentId,
        UUID merchantId,
        long amountMinor,
        String currency,
        String status,
        String previousStatus,
        long eventAmountMinor,
        String merchantContactEmail,
        String merchantWebhookUrl) {
}
