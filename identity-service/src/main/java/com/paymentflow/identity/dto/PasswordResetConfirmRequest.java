package com.paymentflow.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Password length is capped at 72 (BCrypt's input limit) — same rule as {@link RegisterRequest}. */
public record PasswordResetConfirmRequest(
        @NotBlank String token,
        @NotBlank @Size(min = 8, max = 72) String newPassword) {
}
