package com.paymentflow.merchant.web;

import com.paymentflow.common.dto.page.PageResponse;
import com.paymentflow.merchant.dto.ApiKeyIssuedResponse;
import com.paymentflow.merchant.dto.MerchantOnboardResponse;
import com.paymentflow.merchant.dto.MerchantResponse;
import com.paymentflow.merchant.dto.OnboardMerchantRequest;
import com.paymentflow.merchant.dto.UpdateMerchantRequest;
import com.paymentflow.merchant.service.MerchantService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Merchant onboarding and self-service profile/API-key management. Ownership (does
 * this token's subject own this merchant) is derived from the JWT subject, not a
 * path parameter — a merchant can only ever act on their own profile. Listing all
 * merchants is ADMIN-only.
 */
@RestController
@RequestMapping("/api/v1/merchants")
public class MerchantController {

    private final MerchantService merchantService;

    public MerchantController(MerchantService merchantService) {
        this.merchantService = merchantService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MerchantOnboardResponse onboard(@AuthenticationPrincipal Jwt jwt,
                                           @Valid @RequestBody OnboardMerchantRequest request) {
        return merchantService.onboard(ownerUserId(jwt), request);
    }

    @GetMapping("/me")
    public MerchantResponse currentMerchant(@AuthenticationPrincipal Jwt jwt) {
        return merchantService.getMine(ownerUserId(jwt));
    }

    @PatchMapping("/me")
    public MerchantResponse updateCurrentMerchant(@AuthenticationPrincipal Jwt jwt,
                                                  @Valid @RequestBody UpdateMerchantRequest request) {
        return merchantService.updateMine(ownerUserId(jwt), request);
    }

    @PostMapping("/me/api-key/rotate")
    public ApiKeyIssuedResponse rotateApiKey(@AuthenticationPrincipal Jwt jwt) {
        return merchantService.rotateMyApiKey(ownerUserId(jwt));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<MerchantResponse> listMerchants(Pageable pageable) {
        return merchantService.list(pageable);
    }

    private static UUID ownerUserId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
