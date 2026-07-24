package com.paymentflow.payment.authorization.sandbox;

import java.util.UUID;

/**
 * The wire shape of sandbox-service's {@code POST /internal/v1/sandbox/decisions}
 * request body — a payment-service-local projection (D4's schema-per-service
 * philosophy applied to REST contracts, mirroring {@code MerchantSummary}'s precedent),
 * not a DTO shared across the module boundary. {@code merchantId}/{@code mode} are
 * never carried here — sandbox-service derives both from the signed internal-context
 * headers {@link SandboxClient} sends alongside this body (§7 barrier ①).
 */
public record SandboxDecisionRequest(
        String decisionKey,
        UUID paymentId,
        String operation,
        String paymentMethodToken,
        long amountMinor,
        String currency) {
}
