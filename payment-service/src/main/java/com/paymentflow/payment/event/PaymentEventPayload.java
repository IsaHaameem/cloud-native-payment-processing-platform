package com.paymentflow.payment.event;

import java.util.UUID;

/**
 * The JSON shape published for every payment lifecycle event. Deliberately local to
 * payment-service, not common-dto: consumers (transaction-service, audit-service, ...
 * from M6+) define their own copy matching this shape rather than importing this
 * class, so no consumer ever compiles against payment-service's internal model
 * (schema-per-service, D4, extended to messaging contracts).
 */
public record PaymentEventPayload(
        UUID paymentId,
        UUID merchantId,
        long amountMinor,
        String currency,
        String status,
        String previousStatus) {
}
