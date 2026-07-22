package com.paymentflow.merchant.dto;

import com.paymentflow.merchant.domain.ApiKeyType;
import com.paymentflow.merchant.domain.KeyMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * {@code scopes} is optional: omitted, a publishable key defaults to
 * {@code ["payments:read"]} and a secret key to {@code ["*"]} — the type-appropriate
 * default from §4.3 ("publishable keys are read-only; secret keys are full-access").
 */
public record CreateApiKeyRequest(
        @Size(max = 100) String name,
        @NotNull ApiKeyType type,
        @NotNull KeyMode mode,
        List<String> scopes) {
}
