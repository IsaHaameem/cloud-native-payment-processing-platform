package com.paymentflow.merchant.service;

import com.paymentflow.common.exception.ResourceNotFoundException;
import com.paymentflow.common.security.OpaqueTokenGenerator;
import com.paymentflow.merchant.config.ApiKeyProperties;
import com.paymentflow.merchant.domain.ApiKey;
import com.paymentflow.merchant.domain.ApiKeyType;
import com.paymentflow.merchant.domain.KeyMode;
import com.paymentflow.merchant.event.MerchantEventPublisher;
import com.paymentflow.merchant.repository.ApiKeyRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Issues, lists, rotates, revokes, and verifies merchant API keys (M15, D99 — replaces
 * V1's single-active-key model, D29). A raw high-entropy secret is returned once while
 * only its SHA-256 hash is persisted (mirrors identity-service's refresh-token
 * pattern, D27); many keys may be concurrently active per merchant.
 */
@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);
    private static final int VISIBLE_PREFIX_LENGTH = 12;
    private static final String LAST_USED_THROTTLE_KEY_PREFIX = "apikey:lastused:";
    /** Shared with gateway-service's ApiKeyCacheService — the same Redis instance, same namespace. */
    private static final String VERIFY_CACHE_KEY_PREFIX = "apikey:v1:";

    private final ApiKeyRepository apiKeyRepository;
    private final ApiKeyProperties apiKeyProperties;
    private final StringRedisTemplate redisTemplate;
    private final MerchantEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    public ApiKeyService(ApiKeyRepository apiKeyRepository, ApiKeyProperties apiKeyProperties,
                         StringRedisTemplate redisTemplate, MerchantEventPublisher eventPublisher,
                         MeterRegistry meterRegistry) {
        this.apiKeyRepository = apiKeyRepository;
        this.apiKeyProperties = apiKeyProperties;
        this.redisTemplate = redisTemplate;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public IssuedApiKey issue(UUID merchantId, ApiKeyType type, KeyMode mode, String name, List<String> scopes) {
        List<String> effectiveScopes = (scopes == null || scopes.isEmpty()) ? defaultScopes(type) : scopes;
        String effectiveName = (name == null || name.isBlank()) ? defaultName(type, mode) : name;

        String rawKey = type.prefix() + "_" + mode.value() + "_" + ApiKeySecretGenerator.generate();
        String visiblePrefix = rawKey.substring(0, Math.min(VISIBLE_PREFIX_LENGTH, rawKey.length()));

        ApiKey saved = apiKeyRepository.save(ApiKey.issue(merchantId, type, mode, effectiveName, visiblePrefix,
                OpaqueTokenGenerator.sha256Hex(rawKey), effectiveScopes, null));
        eventPublisher.publishApiKeyEvent("ApiKeyIssued", saved);
        meterRegistry.counter("api_key_issued_total", "type", type.name(), "mode", mode.name()).increment();
        return new IssuedApiKey(rawKey, saved);
    }

    /** The four default keys every merchant is issued at onboarding (§3.1 step 2). */
    @Transactional
    public List<IssuedApiKey> issueDefaultSet(UUID merchantId) {
        return List.of(
                issue(merchantId, ApiKeyType.PUBLISHABLE, KeyMode.TEST, null, null),
                issue(merchantId, ApiKeyType.SECRET, KeyMode.TEST, null, null),
                issue(merchantId, ApiKeyType.PUBLISHABLE, KeyMode.LIVE, null, null),
                issue(merchantId, ApiKeyType.SECRET, KeyMode.LIVE, null, null));
    }

    @Transactional(readOnly = true)
    public List<ApiKey> list(UUID merchantId) {
        return apiKeyRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }

    @Transactional
    public void revoke(UUID merchantId, UUID keyId) {
        ApiKey key = ownedKey(merchantId, keyId);
        key.revoke();
        evictVerifyCache(key);
        eventPublisher.publishApiKeyEvent("ApiKeyRevoked", key);
        meterRegistry.counter("api_key_revoked_total").increment();
    }

    /** Issues a replacement key with the same type/mode/name/scopes and grants the old one a grace window. */
    @Transactional
    public IssuedApiKey rotateWithGrace(UUID merchantId, UUID keyId) {
        ApiKey existing = ownedKey(merchantId, keyId);
        existing.rotateWithGrace(apiKeyProperties.rotationGracePeriod());
        eventPublisher.publishApiKeyEvent("ApiKeyRotated", existing);
        meterRegistry.counter("api_key_rotated_total").increment();
        return issue(merchantId, existing.getKeyType(), existing.getMode(), existing.getName(), existing.getScopes());
    }

    /**
     * Deletes the gateway's cached verification result for this key outright (M15's
     * own risk mitigation for "cached key context outlives a revocation") — a genuine
     * revoke must take effect immediately, not just after the cache's TTL lapses. Not
     * called on rotate-with-grace: the old key is still meant to keep authenticating
     * during its grace window, so its cache entry is deliberately left to expire on
     * its own short TTL instead.
     */
    private void evictVerifyCache(ApiKey key) {
        redisTemplate.delete(VERIFY_CACHE_KEY_PREFIX + key.getKeyHash());
    }

    /** Constant-time hash lookup (D27's established pattern); returns empty for any invalid/inactive key. */
    @Transactional
    public Optional<ApiKey> verify(String rawKey) {
        Optional<ApiKey> found = apiKeyRepository.findByKeyHash(OpaqueTokenGenerator.sha256Hex(rawKey))
                .filter(key -> key.isActive(Instant.now()));
        found.ifPresent(this::touchLastUsedThrottled);
        return found;
    }

    /**
     * Writes {@code last_used_at} at most once per {@code lastUsedThrottle} window per
     * key — never once per request (task 2's explicit constraint) — via a short-TTL
     * Redis marker acting as a distributed throttle. Fire-and-forget on the JVM's
     * common pool: a missed or delayed timestamp update is never worth failing, or even
     * slowing down, the request that triggered it.
     */
    private void touchLastUsedThrottled(ApiKey key) {
        String throttleKey = LAST_USED_THROTTLE_KEY_PREFIX + key.getId();
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(throttleKey, "1", apiKeyProperties.lastUsedThrottle());
        if (!Boolean.TRUE.equals(acquired)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                apiKeyRepository.findById(key.getId()).ifPresent(fresh -> {
                    fresh.touchLastUsed();
                    apiKeyRepository.save(fresh);
                });
            } catch (Exception e) {
                log.warn("Failed to persist last_used_at for API key {}", key.getId(), e);
            }
        });
    }

    private ApiKey ownedKey(UUID merchantId, UUID keyId) {
        return apiKeyRepository.findByIdAndMerchantId(keyId, merchantId)
                .orElseThrow(() -> ResourceNotFoundException.of("ApiKey", keyId));
    }

    private static List<String> defaultScopes(ApiKeyType type) {
        return type == ApiKeyType.PUBLISHABLE ? List.of("payments:read") : List.of("*");
    }

    private static String defaultName(ApiKeyType type, KeyMode mode) {
        return "Default " + mode.value() + " " + type.name().toLowerCase() + " key";
    }

    /** The raw key value (shown once) and the persisted entity it corresponds to. */
    public record IssuedApiKey(String rawValue, ApiKey apiKey) {
    }
}
