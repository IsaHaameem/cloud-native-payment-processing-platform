package com.paymentflow.gateway.security.apikey;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The gateway's own local copy of merchant-service's {@code /internal/v1/api-keys/verify}
 * response shape (D36's schema-per-service-messaging convention, applied here to an
 * internal REST contract rather than a Kafka payload). {@code mode} is normalized to
 * lowercase ({@code "test"}/{@code "live"}) on arrival — merchant-service serializes its
 * {@code KeyMode} enum as {@code "TEST"}/{@code "LIVE"}.
 */
public record ApiKeyVerifyResult(
        UUID merchantId,
        UUID keyId,
        String mode,
        List<String> scopes,
        String contactEmail,
        String webhookUrl) {

    public ApiKeyVerifyResult {
        mode = mode == null ? null : mode.toLowerCase(Locale.ROOT);
    }
}
