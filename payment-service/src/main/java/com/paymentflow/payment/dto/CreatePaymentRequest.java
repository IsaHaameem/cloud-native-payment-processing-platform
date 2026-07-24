package com.paymentflow.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * {@code paymentMethodToken} (M17, D130) is optional — a payment created without one
 * behaves exactly as every payment did before M17 (mode-default authorization,
 * M17.4). No validation beyond a length bound: the token's meaning (a known test card,
 * or unrecognised) is sandbox-service's business, not payment-service's.
 */
public record CreatePaymentRequest(
        @Positive long amountMinor,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @Size(max = 500) String description,
        @Size(max = 64) String paymentMethodToken) {
}
