package com.paymentflow.merchant.web;

import com.paymentflow.merchant.dto.ApiKeyIssuedResponse;
import com.paymentflow.merchant.dto.ApiKeyResponse;
import com.paymentflow.merchant.dto.CreateApiKeyRequest;
import com.paymentflow.merchant.mapper.ApiKeyMapper;
import com.paymentflow.merchant.service.ApiKeyService;
import com.paymentflow.merchant.service.MerchantService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Dashboard-facing key management (M15) — JWT-authenticated, the {@code /api/v1}
 * tier (D98). Ownership is derived from the JWT subject via {@code MerchantService},
 * never a path parameter, preserving D28's no-IDOR-surface-by-construction property.
 */
@RestController
@RequestMapping("/api/v1/merchants/me/api-keys")
public class ApiKeyController {

    private final MerchantService merchantService;
    private final ApiKeyService apiKeyService;
    private final ApiKeyMapper apiKeyMapper;

    public ApiKeyController(MerchantService merchantService, ApiKeyService apiKeyService, ApiKeyMapper apiKeyMapper) {
        this.merchantService = merchantService;
        this.apiKeyService = apiKeyService;
        this.apiKeyMapper = apiKeyMapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeyIssuedResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateApiKeyRequest request) {
        UUID merchantId = merchantService.getMine(ownerUserId(jwt)).id();
        ApiKeyService.IssuedApiKey issued =
                apiKeyService.issue(merchantId, request.type(), request.mode(), request.name(), request.scopes());
        return apiKeyMapper.toIssuedResponse(issued);
    }

    @GetMapping
    public List<ApiKeyResponse> list(@AuthenticationPrincipal Jwt jwt) {
        UUID merchantId = merchantService.getMine(ownerUserId(jwt)).id();
        return apiKeyService.list(merchantId).stream().map(apiKeyMapper::toResponse).toList();
    }

    @PostMapping("/{id}/rotate")
    public ApiKeyIssuedResponse rotate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID merchantId = merchantService.getMine(ownerUserId(jwt)).id();
        return apiKeyMapper.toIssuedResponse(apiKeyService.rotateWithGrace(merchantId, id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID merchantId = merchantService.getMine(ownerUserId(jwt)).id();
        apiKeyService.revoke(merchantId, id);
    }

    private static UUID ownerUserId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
