package com.paymentflow.merchant.service;

import com.paymentflow.common.dto.page.PageResponse;
import com.paymentflow.common.exception.ResourceNotFoundException;
import com.paymentflow.merchant.domain.Merchant;
import com.paymentflow.merchant.dto.MerchantOnboardResponse;
import com.paymentflow.merchant.dto.MerchantResponse;
import com.paymentflow.merchant.dto.OnboardMerchantRequest;
import com.paymentflow.merchant.dto.UpdateMerchantRequest;
import com.paymentflow.merchant.dto.UpdateWebhookRequest;
import com.paymentflow.merchant.event.MerchantEventPublisher;
import com.paymentflow.merchant.exception.MerchantAlreadyExistsException;
import com.paymentflow.merchant.mapper.ApiKeyMapper;
import com.paymentflow.merchant.mapper.MerchantMapper;
import com.paymentflow.merchant.repository.MerchantRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class MerchantService {

    private final MerchantRepository merchantRepository;
    private final ApiKeyService apiKeyService;
    private final ApiKeyMapper apiKeyMapper;
    private final MerchantMapper merchantMapper;
    private final MerchantEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    public MerchantService(MerchantRepository merchantRepository, ApiKeyService apiKeyService,
                           ApiKeyMapper apiKeyMapper, MerchantMapper merchantMapper,
                           MerchantEventPublisher eventPublisher, MeterRegistry meterRegistry) {
        this.merchantRepository = merchantRepository;
        this.apiKeyService = apiKeyService;
        this.apiKeyMapper = apiKeyMapper;
        this.merchantMapper = merchantMapper;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public MerchantOnboardResponse onboard(UUID ownerUserId, OnboardMerchantRequest request) {
        if (merchantRepository.existsByOwnerUserId(ownerUserId)) {
            throw new MerchantAlreadyExistsException(ownerUserId);
        }
        Merchant merchant = merchantRepository.save(
                Merchant.onboard(ownerUserId, request.businessName(), request.contactEmail()));
        eventPublisher.publishMerchantOnboarded(merchant);

        var issuedKeys = apiKeyService.issueDefaultSet(merchant.getId()).stream()
                .map(apiKeyMapper::toIssuedResponse)
                .toList();
        meterRegistry.counter("merchant_onboarded_total").increment();
        return new MerchantOnboardResponse(merchantMapper.toResponse(merchant), issuedKeys);
    }

    @Cacheable(cacheNames = "merchants", key = "#ownerUserId")
    public MerchantResponse getMine(UUID ownerUserId) {
        return merchantRepository.findByOwnerUserId(ownerUserId)
                .map(merchantMapper::toResponse)
                .orElseThrow(() -> ResourceNotFoundException.of("Merchant", ownerUserId));
    }

    @CacheEvict(cacheNames = "merchants", key = "#ownerUserId")
    @Transactional
    public MerchantResponse updateMine(UUID ownerUserId, UpdateMerchantRequest request) {
        Merchant merchant = merchantRepository.findByOwnerUserId(ownerUserId)
                .orElseThrow(() -> ResourceNotFoundException.of("Merchant", ownerUserId));
        merchant.updateProfile(request.businessName(), request.contactEmail());
        return merchantMapper.toResponse(merchant);
    }

    @CacheEvict(cacheNames = "merchants", key = "#ownerUserId")
    @Transactional
    public MerchantResponse updateMyWebhook(UUID ownerUserId, UpdateWebhookRequest request) {
        Merchant merchant = merchantRepository.findByOwnerUserId(ownerUserId)
                .orElseThrow(() -> ResourceNotFoundException.of("Merchant", ownerUserId));
        merchant.updateWebhookUrl(request.webhookUrl());
        return merchantMapper.toResponse(merchant);
    }

    // Single-key rotation (rotateMyApiKey) is superseded by ApiKeyController's
    // multi-key rotate-with-grace endpoint (M15, D99).

    public PageResponse<MerchantResponse> list(Pageable pageable) {
        Page<MerchantResponse> page = merchantRepository.findAll(pageable).map(merchantMapper::toResponse);
        return PageResponse.of(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements());
    }
}
