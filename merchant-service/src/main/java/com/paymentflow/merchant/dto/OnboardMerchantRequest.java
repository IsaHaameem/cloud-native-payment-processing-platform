package com.paymentflow.merchant.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OnboardMerchantRequest(
        @NotBlank @Size(max = 200) String businessName,
        @NotBlank @Email @Size(max = 255) String contactEmail) {
}
