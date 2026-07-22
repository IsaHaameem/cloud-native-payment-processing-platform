package com.paymentflow.gateway.security.apikey;

import com.paymentflow.gateway.config.ApiKeyCacheProperties;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * Redis-backed verification cache (M15, §4.8: {@code apikey:v1:<sha256>}) with brief
 * negative caching (task 5) — the same key namespace merchant-service's
 * {@code ApiKeyService} evicts from directly on revoke/rotate, so a revoked key stops
 * authenticating within one eviction call, not just after its TTL lapses.
 */
@Component
public class ApiKeyCacheService {

    /** Shared with merchant-service's {@code ApiKeyService} — the eviction-on-revoke namespace. */
    public static final String KEY_PREFIX = "apikey:v1:";

    private static final String NEGATIVE_SENTINEL = "NEGATIVE";

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ApiKeyCacheProperties properties;

    public ApiKeyCacheService(ReactiveStringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                              ApiKeyCacheProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Mono<CacheResult> lookup(String keyHash) {
        return redisTemplate.opsForValue().get(KEY_PREFIX + keyHash)
                .map(this::parse)
                .defaultIfEmpty(new Miss());
    }

    public Mono<Boolean> putPositive(String keyHash, ApiKeyVerifyResult result) {
        String json = objectMapper.writeValueAsString(result);
        return redisTemplate.opsForValue().set(KEY_PREFIX + keyHash, json, properties.positiveTtl());
    }

    public Mono<Boolean> putNegative(String keyHash) {
        return redisTemplate.opsForValue().set(KEY_PREFIX + keyHash, NEGATIVE_SENTINEL, properties.negativeTtl());
    }

    private CacheResult parse(String raw) {
        if (NEGATIVE_SENTINEL.equals(raw)) {
            return new Negative();
        }
        try {
            return new Hit(objectMapper.readValue(raw, ApiKeyVerifyResult.class));
        } catch (Exception e) {
            // A corrupt/unparseable entry is treated as a miss, not a fatal error —
            // the caller re-verifies against merchant-service and repairs the cache.
            return new Miss();
        }
    }

    public sealed interface CacheResult permits Hit, Negative, Miss {
    }

    public record Hit(ApiKeyVerifyResult result) implements CacheResult {
    }

    public record Negative() implements CacheResult {
    }

    public record Miss() implements CacheResult {
    }
}
