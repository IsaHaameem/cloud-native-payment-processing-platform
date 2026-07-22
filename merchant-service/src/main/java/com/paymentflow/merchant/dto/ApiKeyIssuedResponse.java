package com.paymentflow.merchant.dto;

import com.paymentflow.merchant.domain.ApiKeyType;
import com.paymentflow.merchant.domain.KeyMode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The raw API key value — returned exactly once, at issuance/rotation time. Only its hash is ever stored. */
public record ApiKeyIssuedResponse(
        UUID id,
        String apiKey,
        String keyPrefix,
        ApiKeyType type,
        KeyMode mode,
        String name,
        List<String> scopes,
        Instant expiresAt,
        Instant issuedAt) {
}
