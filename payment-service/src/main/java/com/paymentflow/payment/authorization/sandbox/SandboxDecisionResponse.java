package com.paymentflow.payment.authorization.sandbox;

/**
 * The wire shape of sandbox-service's decision response — a payment-service-local
 * projection, same rationale as {@link SandboxDecisionRequest}. {@code source}/
 * {@code latencyMs}/{@code deferredOperation}/{@code deferredDelayMs} are read by
 * nothing outside this package: {@link SandboxAuthorizationAdvisor} is the only code
 * that ever sees this type, and it is what translates {@code outcome}/
 * {@code declineCode}/{@code errorCode} into the neutral
 * {@code com.paymentflow.payment.authorization.AuthorizationDecision} the port actually
 * exposes (D132) — every sandbox-specific field here stops at this adapter.
 */
public record SandboxDecisionResponse(
        String outcome,
        String declineCode,
        String errorCode,
        int latencyMs,
        String source,
        String deferredOperation,
        Integer deferredDelayMs) {
}
