package com.paymentflow.merchant.web;

import com.paymentflow.common.exception.ResourceNotFoundException;
import com.paymentflow.merchant.domain.ApiKey;
import com.paymentflow.merchant.dto.ApiKeyVerifyRequest;
import com.paymentflow.merchant.dto.ApiKeyVerifyResponse;
import com.paymentflow.merchant.repository.MerchantRepository;
import com.paymentflow.merchant.service.ApiKeyService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service only (M15, {@code /internal/v1} tier, D98) — the gateway's sole
 * merchant-service dependency for the API-key authentication path (§4.3). Never
 * routed through the gateway (no route predicate matches {@code /internal/v1/**}) and
 * additionally denied a JWT requirement at the security-config layer, since its only
 * legitimate caller has no JWT to present.
 *
 * <p>A key that doesn't verify (unknown, revoked, expired, or past its grace window)
 * surfaces as 404 here — the internal contract's own "not found" semantics, not to be
 * confused with the 401 the gateway then returns to the actual client.
 */
@RestController
@RequestMapping("/internal/v1/api-keys")
public class ApiKeyInternalController {

    private final ApiKeyService apiKeyService;
    private final MerchantRepository merchantRepository;

    public ApiKeyInternalController(ApiKeyService apiKeyService, MerchantRepository merchantRepository) {
        this.apiKeyService = apiKeyService;
        this.merchantRepository = merchantRepository;
    }

    @PostMapping("/verify")
    public ApiKeyVerifyResponse verify(@Valid @RequestBody ApiKeyVerifyRequest request) {
        ApiKey key = apiKeyService.verify(request.apiKey())
                .orElseThrow(() -> new ResourceNotFoundException("The API key is invalid, revoked, or expired."));
        var merchant = merchantRepository.findById(key.getMerchantId())
                .orElseThrow(() -> ResourceNotFoundException.of("Merchant", key.getMerchantId()));

        return new ApiKeyVerifyResponse(
                key.getMerchantId(), key.getId(), key.getMode(), key.getScopes(),
                merchant.getContactEmail(), merchant.getWebhookUrl());
    }
}
