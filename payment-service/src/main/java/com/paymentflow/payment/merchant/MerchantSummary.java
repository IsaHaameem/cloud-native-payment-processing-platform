package com.paymentflow.payment.merchant;

import java.util.UUID;

/**
 * The only field of merchant-service's {@code MerchantResponse} payment-service
 * actually needs. Deliberately not a shared DTO (Boot's default Jackson config
 * ignores unknown properties, so this narrow projection deserializes fine against
 * merchant-service's full response) — mirrors the schema-per-service philosophy (D4)
 * applied to REST contracts, not just databases.
 */
public record MerchantSummary(UUID id) {
}
