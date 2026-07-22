package com.paymentflow.merchant.event;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/**
 * Local to merchant-service (D36 — only the structural {@code EventEnvelope<T>}
 * wrapper is shared; consumers define their own copy of this shape). Covers both
 * merchant-lifecycle and key-lifecycle events with one payload, mirroring how
 * {@code PaymentEventPayload} carries a superset of fields across every payment
 * transition type — fields that don't apply to a given {@code eventType} are simply
 * null (e.g. no {@code apiKeyId} on {@code MerchantOnboarded}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MerchantEventPayload(
        UUID merchantId,
        String businessName,
        String contactEmail,
        UUID apiKeyId,
        String keyPrefix,
        String keyType,
        String keyMode,
        List<String> scopes) {
}
