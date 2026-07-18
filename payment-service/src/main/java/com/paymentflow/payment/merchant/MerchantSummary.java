package com.paymentflow.payment.merchant;

import java.util.UUID;

/**
 * The fields of merchant-service's {@code MerchantResponse} payment-service actually
 * needs. Deliberately not a shared DTO (Boot's default Jackson config ignores unknown
 * properties, so this narrow projection deserializes fine against merchant-service's
 * full response) — mirrors the schema-per-service philosophy (D4) applied to REST
 * contracts, not just databases.
 *
 * <p>{@code contactEmail} and {@code webhookUrl} are carried here (not just {@code id})
 * so they can be embedded directly into {@code PaymentEventPayload} at publish time —
 * notification-service (M7) then needs no synchronous call back to merchant-service to
 * learn where to deliver, staying a pure async consumer (D43).
 */
public record MerchantSummary(UUID id, String contactEmail, String webhookUrl) {
}
