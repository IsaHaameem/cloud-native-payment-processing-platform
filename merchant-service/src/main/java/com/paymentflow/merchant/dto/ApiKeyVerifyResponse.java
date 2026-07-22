package com.paymentflow.merchant.dto;

import com.paymentflow.merchant.domain.KeyMode;

import java.util.List;
import java.util.UUID;

/**
 * The internal, service-to-service verification contract (M15, {@code
 * /internal/v1/api-keys/verify}) — never routed publicly. Carries {@code
 * contactEmail}/{@code webhookUrl} alongside the key identity (D118, approved
 * extension beyond the milestone's original minimal shape) so a caller on the
 * API-key path never needs a second lookup merely to learn them, preserving D43's
 * event-carried delivery info without a feature regression.
 */
public record ApiKeyVerifyResponse(
        UUID merchantId,
        UUID keyId,
        KeyMode mode,
        List<String> scopes,
        String contactEmail,
        String webhookUrl) {
}
