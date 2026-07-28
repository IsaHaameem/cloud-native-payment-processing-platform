package com.paymentflow.gateway.ratelimit;

import com.paymentflow.common.error.CommonErrorCode;
import com.paymentflow.gateway.config.RateLimitProperties;
import com.paymentflow.gateway.security.GatewayErrorResponseWriter;
import com.paymentflow.gateway.security.apikey.ApiKeyAuthenticationWebFilter;
import com.paymentflow.gateway.security.apikey.ApiKeyVerifyResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Per-key, per-mode rate limiting and daily quotas for API-key traffic (M20.5, §5/M20 task 3
 * and 4, <b>D146</b>).
 *
 * <p><b>Why this exists rather than a swapped `KeyResolver`.</b> §5/M20 task 3 says "replacing
 * D24's key resolver", and doing literally that would deliver none of the same milestone's
 * listed features: Spring Cloud Gateway's `RedisRateLimiter` emits `X-RateLimit-*` rather than
 * the standard `RateLimit-*` headers M22's SDKs will back off on, and it has no notion of a
 * daily quota, a per-merchant limit, or separate test/live budgets. D146 records the divergence.
 *
 * <p><b>Scoped to key traffic, and only key traffic.</b> D24's IP/JWT bucket is untouched for
 * dashboard and unauthenticated routes — V1's Gatling rate-limit scenario is M20's stated
 * regression gate, and that behaviour must not move. The two are kept from double-counting by
 * {@code RateLimiterConfig}'s resolver, which returns empty for API-key requests so the built-in
 * filter skips them; this filter then owns that traffic exclusively. Before M20.5 those requests
 * fell into the shared IP bucket, which §14 recorded as a known gap: one busy merchant's server
 * could exhaust an allowance shared with everyone behind the same address.
 *
 * <p><b>Headers report the quota, not the bucket.</b> There are two limits, and the draft
 * `RateLimit-*` standard describes one window, so a choice is forced. The daily quota is what a
 * client can plan around — it is the durable budget, and its reset is a real, meaningful moment —
 * whereas the per-second bucket is a burst guard whose "reset" is under a second away and would
 * make `RateLimit-Reset: 0` on nearly every response. The bucket therefore surfaces only when it
 * actually refuses something, as a 429 with `Retry-After`. Both causes are distinguishable by the
 * error code in the body.
 */
@Component
public class ApiKeyRateLimitWebFilter implements WebFilter, Ordered {


    static final String HEADER_LIMIT = "RateLimit-Limit";
    static final String HEADER_REMAINING = "RateLimit-Remaining";
    static final String HEADER_RESET = "RateLimit-Reset";

    private static final Logger log = LoggerFactory.getLogger(ApiKeyRateLimitWebFilter.class);
    private static final String BUCKET_KEY_PREFIX = "ratelimit:key:";
    private static final String QUOTA_KEY_PREFIX = "quota:";
    private static final long QUOTA_TTL_SECONDS = Duration.ofHours(48).toSeconds();
    private static final int QUOTA_REFUSED = -1;

    private final ReactiveStringRedisTemplate redisTemplate;
    private final RedisScript<List> script;
    private final RateLimitProperties properties;
    private final GatewayErrorResponseWriter errorWriter;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    /** Explicit {@code @Autowired} because the clock-injecting constructor below makes two. */
    @org.springframework.beans.factory.annotation.Autowired
    public ApiKeyRateLimitWebFilter(ReactiveStringRedisTemplate redisTemplate,
                                    RedisScript<List> apiKeyRateLimitScript,
                                    RateLimitProperties properties,
                                    GatewayErrorResponseWriter errorWriter,
                                    MeterRegistry meterRegistry) {
        this(redisTemplate, apiKeyRateLimitScript, properties, errorWriter, meterRegistry, Clock.systemUTC());
    }

    ApiKeyRateLimitWebFilter(ReactiveStringRedisTemplate redisTemplate, RedisScript<List> apiKeyRateLimitScript,
                             RateLimitProperties properties, GatewayErrorResponseWriter errorWriter,
                             MeterRegistry meterRegistry, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.script = apiKeyRateLimitScript;
        this.properties = properties;
        this.errorWriter = errorWriter;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Override
    public int getOrder() {
        // After ApiKeyAuthenticationWebFilter (HIGHEST_PRECEDENCE + 20), which resolves the key
        // context this filter limits on, and inside the request-logging filter at +10 so a 429
        // is captured in the developer's own request log — which is the whole point of logging
        // refusals (M20.2).
        return Ordered.HIGHEST_PRECEDENCE + 30;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!properties.enabled()) {
            return chain.filter(exchange);
        }
        Object attribute = exchange.getAttributes().get(ApiKeyAuthenticationWebFilter.RESOLVED_KEY_CONTEXT_ATTRIBUTE);
        if (!(attribute instanceof ApiKeyVerifyResult context)) {
            // Not API-key traffic: D24's IP/JWT bucket already handled it.
            return chain.filter(exchange);
        }

        EffectiveLimits limits = resolve(context);
        Instant now = clock.instant();

        return evaluate(context, limits, now)
                .flatMap(decision -> decision.allowed()
                        ? proceed(exchange, chain, limits, decision, now)
                        : refuse(exchange, limits, decision, now))
                // Redis being unavailable must not take the platform down with it. Failing
                // *open* is the deliberate choice: rate limiting protects against excess load,
                // and refusing every request because the limiter is unreachable converts a
                // capacity safeguard into a total outage — a strictly worse failure than
                // briefly serving unlimited traffic. Counted so it is never invisible.
                .onErrorResume(error -> {
                    meterRegistry.counter("api_key_rate_limit_total", "outcome", "error").increment();
                    log.warn("Rate-limit evaluation failed for key {} — allowing the request", context.keyId(), error);
                    return chain.filter(exchange);
                });
    }

    /** Merchant override if present, otherwise the platform default for the request's mode. */
    private EffectiveLimits resolve(ApiKeyVerifyResult context) {
        RateLimitProperties.Budget budget = properties.budgetFor(context.mode());
        int rate = context.rateLimitPerSecond() != null ? context.rateLimitPerSecond() : budget.requestsPerSecond();
        int burst = context.rateLimitBurst() != null ? context.rateLimitBurst() : budget.burst();
        int quota = context.dailyQuota() != null ? context.dailyQuota() : budget.dailyQuota();
        // A merchant override that set a burst below the rate would cap throughput below the
        // rate they were told they had; the schema rejects non-positive values, and this makes
        // the remaining inconsistency harmless rather than silently throttling.
        return new EffectiveLimits(rate, Math.max(burst, rate), quota);
    }

    @SuppressWarnings("unchecked")
    private Mono<Decision> evaluate(ApiKeyVerifyResult context, EffectiveLimits limits, Instant now) {
        String day = LocalDate.ofInstant(now, ZoneOffset.UTC).toString();
        // Bucket is per key: two keys belonging to one merchant get independent burst capacity,
        // so a runaway script on one key cannot starve another. Quota is per merchant and mode:
        // the budget is the merchant's, and issuing another key must not multiply it.
        String bucketKey = BUCKET_KEY_PREFIX + context.keyId();
        String quotaKey = QUOTA_KEY_PREFIX + context.merchantId() + ":" + context.mode() + ":" + day;

        return redisTemplate
                .execute(script, List.of(bucketKey, bucketKey + ":ts", quotaKey),
                        List.of(String.valueOf(limits.requestsPerSecond()),
                                String.valueOf(limits.burst()),
                                String.valueOf(now.getEpochSecond()),
                                "1",
                                String.valueOf(limits.dailyQuota()),
                                String.valueOf(QUOTA_TTL_SECONDS)))
                .next()
                .map(raw -> Decision.from((List<Long>) raw));
    }

    private Mono<Void> proceed(ServerWebExchange exchange, WebFilterChain chain, EffectiveLimits limits,
                               Decision decision, Instant now) {
        meterRegistry.counter("api_key_rate_limit_total", "outcome", "allowed").increment();
        exchange.getResponse().beforeCommit(() -> {
            applyQuotaHeaders(exchange, limits, decision, now);
            return Mono.empty();
        });
        return chain.filter(exchange);
    }

    private Mono<Void> refuse(ServerWebExchange exchange, EffectiveLimits limits, Decision decision, Instant now) {
        boolean quotaExhausted = decision.retryAfterSeconds() == QUOTA_REFUSED;
        long retryAfter = quotaExhausted ? secondsUntilNextUtcDay(now) : Math.max(1, decision.retryAfterSeconds());

        meterRegistry.counter("api_key_rate_limit_total", "outcome", quotaExhausted ? "quota" : "throttled")
                .increment();

        applyQuotaHeaders(exchange, limits, decision, now);
        exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));

        // Both codes are catalogued (M21.4); their default messages are the ones that used
        // to be written out here, so the response body is unchanged.
        return errorWriter.write(exchange, quotaExhausted
                ? CommonErrorCode.DAILY_QUOTA_EXCEEDED
                : CommonErrorCode.RATE_LIMIT_EXCEEDED);
    }

    /**
     * Reports the daily quota window. Written in {@code beforeCommit} on the success path
     * because a proxied response commits its headers as soon as the body starts streaming —
     * the same trap {@code CorrelationIdWebFilter} documents.
     */
    private void applyQuotaHeaders(ServerWebExchange exchange, EffectiveLimits limits, Decision decision,
                                   Instant now) {
        if (limits.dailyQuota() <= 0) {
            return;
        }
        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.set(HEADER_LIMIT, String.valueOf(limits.dailyQuota()));
        headers.set(HEADER_REMAINING, String.valueOf(Math.max(0, limits.dailyQuota() - decision.quotaUsed())));
        headers.set(HEADER_RESET, String.valueOf(secondsUntilNextUtcDay(now)));
    }

    private static long secondsUntilNextUtcDay(Instant now) {
        Instant nextMidnight = LocalDate.ofInstant(now, ZoneOffset.UTC).plusDays(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        return Math.max(1, Duration.between(now, nextMidnight).toSeconds());
    }

    private record EffectiveLimits(int requestsPerSecond, int burst, int dailyQuota) {
    }

    private record Decision(boolean allowed, long tokensRemaining, long quotaUsed, long retryAfterSeconds) {
        static Decision from(List<Long> raw) {
            return new Decision(raw.get(0) == 1L, raw.get(1), raw.get(2), raw.get(3));
        }
    }
}
