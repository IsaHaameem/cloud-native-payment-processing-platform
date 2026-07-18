package com.paymentflow.merchant.service;

import com.paymentflow.common.security.OpaqueTokenGenerator;
import com.paymentflow.merchant.domain.ApiKey;
import com.paymentflow.merchant.repository.ApiKeyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Issues and rotates merchant API keys: a raw high-entropy secret is returned once
 * while only its SHA-256 hash is persisted (mirrors identity-service's refresh-token
 * pattern). Only one key is ever active per merchant — rotating revokes the current
 * one (kept for audit) and issues a fresh one in its place.
 */
@Service
public class ApiKeyService {

    private static final String KEY_PREFIX_TAG = "pf_";
    private static final int VISIBLE_PREFIX_LENGTH = 12;

    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyService(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    @Transactional
    public IssuedApiKey issue(UUID merchantId) {
        String rawKey = KEY_PREFIX_TAG + OpaqueTokenGenerator.generate();
        String visiblePrefix = rawKey.substring(0, Math.min(VISIBLE_PREFIX_LENGTH, rawKey.length()));
        apiKeyRepository.save(ApiKey.issue(merchantId, visiblePrefix, OpaqueTokenGenerator.sha256Hex(rawKey)));
        return new IssuedApiKey(rawKey, visiblePrefix);
    }

    @Transactional
    public IssuedApiKey rotate(UUID merchantId) {
        // Explicitly flushed: the DB enforces "at most one active key per merchant" via a
        // partial unique index (WHERE revoked_at IS NULL). Hibernate's default flush order
        // is inserts-then-updates regardless of call order, so without this flush the new
        // key's INSERT (from issue(), below) would hit the database before this revoke's
        // UPDATE — momentarily two active rows, tripping the constraint.
        apiKeyRepository.findByMerchantIdAndRevokedAtIsNull(merchantId).ifPresent(existing -> {
            existing.revoke();
            apiKeyRepository.saveAndFlush(existing);
        });
        return issue(merchantId);
    }

    /** The raw key value (shown once) and its clear-text visible prefix. */
    public record IssuedApiKey(String rawValue, String prefix) {
    }
}
