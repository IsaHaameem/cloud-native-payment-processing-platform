package com.paymentflow.merchant.dto;

import jakarta.validation.constraints.NotBlank;

public record ApiKeyVerifyRequest(@NotBlank String apiKey) {
}
