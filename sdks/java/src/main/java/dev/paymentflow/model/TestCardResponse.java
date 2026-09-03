package dev.paymentflow.model;

/**
 * One test-mode payment-method token and the behaviour it forces. Pass {@code token} as
 * {@code paymentMethodToken} when creating a payment to choose the outcome. Authorization and
 * capture can behave differently on purpose ({@code outcome} vs {@code captureBehaviour}) — a
 * card that authorizes cleanly and then fails to capture is a real, easily-missed case.
 */
public record TestCardResponse(
        String brand,
        String captureBehaviour,
        String declineCode,
        Long deferredDelayMs,
        String description,
        String errorCode,
        Long latencyMs,
        String outcome,
        String refundBehaviour,
        String token) {}
