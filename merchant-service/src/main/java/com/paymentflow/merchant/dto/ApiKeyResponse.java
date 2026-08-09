package com.paymentflow.merchant.dto;

import com.paymentflow.merchant.domain.ApiKeyType;
import com.paymentflow.merchant.domain.KeyMode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A key's management-facing view — never carries the raw secret (that's {@link ApiKeyIssuedResponse}, once).
 *
 * <p>{@code graceExpiresAt} is set only by {@code ApiKeyService#rotateWithGrace} and is the moment
 * a rotated-out key stops authenticating. It was added in M23.5 because without it a retired key
 * is indistinguishable from a healthy one over the wire — {@code revokedAt} and {@code expiresAt}
 * are both null after a rotation — so the dashboard could not say which of two identically named
 * keys was dying, or when. This DTO is on the {@code /api/v1} account plane, which
 * {@code PublicApiDocumentContract} asserts is excluded from the published spec, so the field
 * reaches no {@code docs/openapi.yaml} path, SDK or generated client.
 */
public record ApiKeyResponse(
        UUID id,
        ApiKeyType type,
        KeyMode mode,
        String name,
        String keyPrefix,
        List<String> scopes,
        Instant lastUsedAt,
        Instant expiresAt,
        Instant graceExpiresAt,
        Instant revokedAt,
        Instant createdAt) {
}
