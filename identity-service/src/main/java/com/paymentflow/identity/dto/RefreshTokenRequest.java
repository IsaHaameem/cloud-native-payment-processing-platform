package com.paymentflow.identity.dto;

import jakarta.validation.constraints.NotBlank;

/** Used for both token refresh and logout (both present an existing refresh token). */
public record RefreshTokenRequest(
        @NotBlank String refreshToken) {
}
