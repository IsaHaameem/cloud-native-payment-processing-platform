package com.paymentflow.sandbox.dto;

public record TestCardResponse(
        String token,
        String brand,
        String outcome,
        String declineCode,
        String errorCode,
        int latencyMs,
        String captureBehaviour,
        String refundBehaviour,
        Integer deferredDelayMs,
        String description) {
}
