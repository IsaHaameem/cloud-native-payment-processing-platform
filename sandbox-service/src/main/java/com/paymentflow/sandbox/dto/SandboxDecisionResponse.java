package com.paymentflow.sandbox.dto;

/**
 * The port-level decision contract this endpoint eventually serves (§12): {@code
 * outcome}/{@code declineCode}/{@code errorCode} plus a {@code PENDING}-style deferred
 * marker are acquirer-neutral. {@code latencyMs} and the raw {@code deferredOperation}/
 * {@code deferredDelayMs} pair are sandbox-specific diagnostics — payment-service's
 * {@code AuthorizationAdvisor} adapter (M17.4) is the only thing that ever reads this
 * DTO, and it is what translates this shape into the neutral
 * {@code AuthorizationDecision} the port actually exposes.
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
