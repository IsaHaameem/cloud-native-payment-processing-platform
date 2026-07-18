package com.paymentflow.merchant.dto;

import java.time.Instant;

/** The raw API key value — returned exactly once, at issuance/rotation time. Only its hash is ever stored. */
public record ApiKeyIssuedResponse(
        String apiKey,
        String keyPrefix,
        Instant issuedAt) {
}
