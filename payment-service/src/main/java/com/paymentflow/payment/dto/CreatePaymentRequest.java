package com.paymentflow.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreatePaymentRequest(
        @Positive long amountMinor,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @Size(max = 500) String description) {
}
