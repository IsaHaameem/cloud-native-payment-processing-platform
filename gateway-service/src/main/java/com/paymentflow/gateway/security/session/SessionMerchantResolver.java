package com.paymentflow.gateway.security.session;

import com.paymentflow.gateway.config.SessionMerchantCacheProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.reactor.timelimiter.TimeLimiterOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * Resolves "which merchant does this session act for?" (M23.0, D183) — the session path's
 * counterpart to the {@code ApiKeyCacheService} + {@code ResilientApiKeyVerifier} pair,
 * collapsed into one class because there is a single cache shape and a single caller.
 *
 * <p>The Redis cache and the Retry → CircuitBreaker → TimeLimiter chain are both the
 * established shapes: the same namespaced-key convention (§4.8), and D49's nesting order
 * with TimeLimiter closest to the call. What differs is deliberate and recorded on
 * {@link SessionMerchantCacheProperties}: positive entries only.
 *
 * <p><b>Why caching this is safe.</b> The identity half of the answer is immutable —
 * a merchant's owner is set at onboarding and merchant-service refuses a second merchant
 * for the same user. Only {@code contactEmail} and {@code webhookUrl} can move, and they
 * are the same two fields the API-key path already serves from a five-minute cache, for the
 * same downstream consumers (D43/D118). This introduces no staleness the platform did not
 * already accept.
 */
@Component
public class SessionMerchantResolver {

    /** §4.8's namespace convention, versioned like {@code apikey:v1:} so a shape change is a new prefix. */
    public static final String KEY_PREFIX = "session:merchant:v1:";

    private static final String INSTANCE_NAME = "sessionMerchantLookup";

    private final SessionMerchantLookupClient client;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final SessionMerchantCacheProperties cacheProperties;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final TimeLimiter timeLimiter;

    public SessionMerchantResolver(SessionMerchantLookupClient client, ReactiveStringRedisTemplate redisTemplate,
                                   ObjectMapper objectMapper, SessionMerchantCacheProperties cacheProperties,
                                   CircuitBreakerRegistry circuitBreakerRegistry, RetryRegistry retryRegistry,
                                   TimeLimiterRegistry timeLimiterRegistry) {
        this.client = client;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.cacheProperties = cacheProperties;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(INSTANCE_NAME);
        this.retry = retryRegistry.retry(INSTANCE_NAME);
        this.timeLimiter = timeLimiterRegistry.timeLimiter(INSTANCE_NAME);
    }

    /**
     * @return the merchant this user owns, or a {@link MerchantNotOnboardedException} signal
     *         if they own none yet.
     */
    public Mono<SessionMerchantResult> resolve(UUID ownerUserId) {
        String cacheKey = KEY_PREFIX + ownerUserId;
        return redisTemplate.opsForValue().get(cacheKey)
                .mapNotNull(this::parse)
                .switchIfEmpty(Mono.defer(() -> lookupAndCache(cacheKey, ownerUserId)));
    }

    private Mono<SessionMerchantResult> lookupAndCache(String cacheKey, UUID ownerUserId) {
        return client.lookup(ownerUserId)
                .transformDeferred(TimeLimiterOperator.of(timeLimiter))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(RetryOperator.of(retry))
                .flatMap(result -> redisTemplate.opsForValue()
                        .set(cacheKey, objectMapper.writeValueAsString(result), cacheProperties.positiveTtl())
                        .thenReturn(result));
    }

    /**
     * A corrupt entry is treated as a miss rather than a failure — the caller re-looks-up and
     * repairs the cache, exactly as {@code ApiKeyCacheService} does. Returning {@code null}
     * is what makes {@code mapNotNull} fall through to {@code switchIfEmpty}.
     */
    private SessionMerchantResult parse(String raw) {
        try {
            return objectMapper.readValue(raw, SessionMerchantResult.class);
        } catch (Exception e) {
            return null;
        }
    }
}
