package com.paymentflow.merchant.dto;

import com.paymentflow.merchant.domain.ApiKeyType;
import com.paymentflow.merchant.domain.KeyMode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A key's management-facing view — never carries the raw secret (that's {@link ApiKeyIssuedResponse}, once). */
public record ApiKeyResponse(
        UUID id,
        ApiKeyType type,
        KeyMode mode,
        String name,
        String keyPrefix,
        List<String> scopes,
        Instant lastUsedAt,
        Instant expiresAt,
        Instant revokedAt,
        Instant createdAt) {
}
