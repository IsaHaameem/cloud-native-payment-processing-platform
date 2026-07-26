package com.paymentflow.gateway.ratelimit;

import com.paymentflow.gateway.config.RateLimitProperties;
import com.paymentflow.gateway.security.GatewayErrorResponseWriter;
import com.paymentflow.gateway.security.apikey.ApiKeyAuthenticationWebFilter;
import com.paymentflow.gateway.security.apikey.ApiKeyVerifyResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M20.5 against a real Redis. The Lua script's atomicity and the bucket's refill arithmetic are
 * the whole substance of this filter, and neither survives being mocked — a fake would assert
 * that the test author understood the algorithm, not that Redis executes it correctly.
 */
@Testcontainers
class ApiKeyRateLimitWebFilterTest {

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379);

    private static ReactiveStringRedisTemplate redisTemplate;
    private static LettuceConnectionFactory connectionFactory;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final GatewayErrorResponseWriter errorWriter =
            new GatewayErrorResponseWriter(new tools.jackson.databind.ObjectMapper());

    private UUID merchantId;
    private UUID keyId;

    @BeforeEach
    void setUp() {
        if (redisTemplate == null) {
            connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
            connectionFactory.afterPropertiesSet();
            redisTemplate = new ReactiveStringRedisTemplate((ReactiveRedisConnectionFactory) connectionFactory);
        }
        // Fresh identifiers per test so the Redis keyspace is naturally isolated without a flush.
        merchantId = UUID.randomUUID();
        keyId = UUID.randomUUID();
    }

    @SuppressWarnings("rawtypes")
    private static RedisScript<List> script() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/api-key-rate-limit.lua"));
        script.setResultType(List.class);
        return script;
    }

    private static RateLimitProperties properties(int rate, int burst, int quota) {
        RateLimitProperties.Budget budget = new RateLimitProperties.Budget(rate, burst, quota);
        return new RateLimitProperties(true, budget, budget);
    }

    private ApiKeyRateLimitWebFilter filter(RateLimitProperties properties, Clock clock) {
        return new ApiKeyRateLimitWebFilter(redisTemplate, script(), properties, errorWriter, meterRegistry, clock);
    }

    private ApiKeyVerifyResult context(Integer rate, Integer burst, Integer quota) {
        return new ApiKeyVerifyResult(merchantId, keyId, "test", List.of("payments:read"),
                "dev@example.com", null, rate, burst, quota);
    }

    private MockServerWebExchange exchange(ApiKeyVerifyResult context) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/payments").build());
        if (context != null) {
            exchange.getAttributes().put(ApiKeyAuthenticationWebFilter.RESOLVED_KEY_CONTEXT_ATTRIBUTE, context);
        }
        return exchange;
    }

    /** A chain that records whether the request was allowed through. */
    private static final class RecordingChain implements org.springframework.web.server.WebFilterChain {
        private boolean invoked;

        @Override
        public Mono<Void> filter(org.springframework.web.server.ServerWebExchange exchange) {
            invoked = true;
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            return exchange.getResponse().setComplete();
        }
    }

    private static Clock fixedAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    @Test
    @DisplayName("requests within the burst are allowed; the one past it is refused with Retry-After")
    void refusesOnceTheBucketIsEmpty() {
        // Frozen clock, so the bucket cannot refill mid-test and the boundary is exact rather
        // than dependent on how fast the test machine runs.
        Clock clock = fixedAt(Instant.parse("2026-07-26T12:00:00Z"));
        ApiKeyRateLimitWebFilter filter = filter(properties(2, 3, 0), clock);
        ApiKeyVerifyResult context = context(null, null, null);

        for (int i = 1; i <= 3; i++) {
            RecordingChain chain = new RecordingChain();
            StepVerifier.create(filter.filter(exchange(context), chain)).verifyComplete();
            assertThat(chain.invoked).as("request %d is within the burst of 3", i).isTrue();
        }

        MockServerWebExchange fourth = exchange(context);
        RecordingChain chain = new RecordingChain();
        StepVerifier.create(filter.filter(fourth, chain)).verifyComplete();

        assertThat(chain.invoked).as("the 4th request must not reach the route").isFalse();
        assertThat(fourth.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(fourth.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
    }

    @Test
    @DisplayName("the bucket refills over time, so a caller that waits is served again")
    void refillsOverTime() {
        Instant start = Instant.parse("2026-07-26T13:00:00Z");
        ApiKeyVerifyResult context = context(null, null, null);

        ApiKeyRateLimitWebFilter atStart = filter(properties(2, 2, 0), fixedAt(start));
        for (int i = 0; i < 2; i++) {
            StepVerifier.create(atStart.filter(exchange(context), new RecordingChain())).verifyComplete();
        }
        MockServerWebExchange refused = exchange(context);
        StepVerifier.create(atStart.filter(refused, new RecordingChain())).verifyComplete();
        assertThat(refused.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // Two seconds later at 2 tokens/sec the bucket is full again.
        ApiKeyRateLimitWebFilter later = filter(properties(2, 2, 0), fixedAt(start.plusSeconds(2)));
        RecordingChain chain = new RecordingChain();
        StepVerifier.create(later.filter(exchange(context), chain)).verifyComplete();
        assertThat(chain.invoked).isTrue();
    }

    @Test
    @DisplayName("the daily quota refuses with its own error code and a reset at midnight UTC")
    void enforcesTheDailyQuota() {
        // Quota 2, burst large enough that the bucket is never the constraint — so the refusal
        // is unambiguously the quota's.
        Clock clock = fixedAt(Instant.parse("2026-07-26T23:00:00Z"));
        ApiKeyRateLimitWebFilter filter = filter(properties(100, 100, 2), clock);
        ApiKeyVerifyResult context = context(null, null, null);

        StepVerifier.create(filter.filter(exchange(context), new RecordingChain())).verifyComplete();
        StepVerifier.create(filter.filter(exchange(context), new RecordingChain())).verifyComplete();

        MockServerWebExchange third = exchange(context);
        RecordingChain chain = new RecordingChain();
        StepVerifier.create(filter.filter(third, chain)).verifyComplete();

        assertThat(chain.invoked).isFalse();
        assertThat(third.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        // 3600s from 23:00 to midnight — the quota resets on the day boundary, not a rolling window.
        assertThat(third.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("3600");
        assertThat(third.getResponse().getHeaders().getFirst("RateLimit-Reset")).isEqualTo("3600");
        assertThat(third.getResponse().getHeaders().getFirst("RateLimit-Remaining")).isEqualTo("0");
    }

    @Test
    @DisplayName("standard RateLimit headers report the quota window on a successful request")
    void reportsQuotaHeaders() {
        Clock clock = fixedAt(Instant.parse("2026-07-26T22:00:00Z"));
        ApiKeyRateLimitWebFilter filter = filter(properties(100, 100, 10), clock);

        MockServerWebExchange exchange = exchange(context(null, null, null));
        StepVerifier.create(filter.filter(exchange, new RecordingChain())).verifyComplete();

        HttpHeaders headers = exchange.getResponse().getHeaders();
        assertThat(headers.getFirst("RateLimit-Limit")).isEqualTo("10");
        assertThat(headers.getFirst("RateLimit-Remaining")).isEqualTo("9");
        assertThat(headers.getFirst("RateLimit-Reset")).isEqualTo("7200");
    }

    @Test
    @DisplayName("a merchant override beats the platform default")
    void merchantOverrideWins() {
        Clock clock = fixedAt(Instant.parse("2026-07-26T12:00:00Z"));
        // Platform default would allow 100; this merchant is capped at a burst of 1.
        ApiKeyRateLimitWebFilter filter = filter(properties(100, 100, 0), clock);
        ApiKeyVerifyResult context = context(1, 1, null);

        StepVerifier.create(filter.filter(exchange(context), new RecordingChain())).verifyComplete();

        MockServerWebExchange second = exchange(context);
        StepVerifier.create(filter.filter(second, new RecordingChain())).verifyComplete();
        assertThat(second.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("a quota refusal does not also drain the burst budget")
    void aQuotaRefusalDoesNotConsumeTokens() {
        // Otherwise a caller out of daily budget would also arrive at tomorrow with an empty
        // bucket and be refused again for a reason that no longer applies.
        Clock clock = fixedAt(Instant.parse("2026-07-26T12:00:00Z"));
        ApiKeyRateLimitWebFilter filter = filter(properties(5, 5, 1), clock);
        ApiKeyVerifyResult context = context(null, null, null);

        StepVerifier.create(filter.filter(exchange(context), new RecordingChain())).verifyComplete();
        for (int i = 0; i < 4; i++) {
            StepVerifier.create(filter.filter(exchange(context), new RecordingChain())).verifyComplete();
        }

        Long tokens = redisTemplate.opsForValue().get("ratelimit:key:" + keyId)
                .map(value -> (long) Double.parseDouble(value)).block();
        assertThat(tokens).as("only the one admitted request should have spent a token").isEqualTo(4L);
    }

    @Test
    @DisplayName("two keys of one merchant have independent buckets but share the daily quota")
    void bucketsArePerKeyAndQuotaIsPerMerchant() {
        // A runaway script on one key must not starve another; but issuing a second key must
        // not double the merchant's daily budget either.
        Clock clock = fixedAt(Instant.parse("2026-07-26T12:00:00Z"));
        ApiKeyRateLimitWebFilter filter = filter(properties(1, 1, 2), clock);

        ApiKeyVerifyResult firstKey = context(null, null, null);
        UUID secondKeyId = UUID.randomUUID();
        ApiKeyVerifyResult secondKey = new ApiKeyVerifyResult(merchantId, secondKeyId, "test",
                List.of("payments:read"), "dev@example.com", null, null, null, null);

        StepVerifier.create(filter.filter(exchange(firstKey), new RecordingChain())).verifyComplete();
        RecordingChain second = new RecordingChain();
        StepVerifier.create(filter.filter(exchange(secondKey), second)).verifyComplete();
        assertThat(second.invoked).as("the second key has its own bucket").isTrue();

        // Both admitted requests counted against the one merchant quota of 2, so a third is refused.
        MockServerWebExchange third = exchange(secondKey);
        StepVerifier.create(filter.filter(third, new RecordingChain())).verifyComplete();
        assertThat(third.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("test and live budgets are counted separately")
    void modesHaveSeparateBudgets() {
        // M16's isolation guarantee extended to capacity: a sandbox load test must not be able
        // to exhaust the allowance production traffic depends on.
        Clock clock = fixedAt(Instant.parse("2026-07-26T12:00:00Z"));
        ApiKeyRateLimitWebFilter filter = filter(properties(100, 100, 1), clock);

        ApiKeyVerifyResult testKey = context(null, null, null);
        ApiKeyVerifyResult liveKey = new ApiKeyVerifyResult(merchantId, keyId, "live",
                List.of("payments:read"), "dev@example.com", null, null, null, null);

        StepVerifier.create(filter.filter(exchange(testKey), new RecordingChain())).verifyComplete();
        MockServerWebExchange testRefused = exchange(testKey);
        StepVerifier.create(filter.filter(testRefused, new RecordingChain())).verifyComplete();
        assertThat(testRefused.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        RecordingChain liveChain = new RecordingChain();
        StepVerifier.create(filter.filter(exchange(liveKey), liveChain)).verifyComplete();
        assertThat(liveChain.invoked).as("live has its own quota and is unaffected").isTrue();
    }

    @Test
    @DisplayName("non-key traffic passes straight through — D24's bucket still owns it")
    void ignoresNonKeyTraffic() {
        ApiKeyRateLimitWebFilter filter = filter(properties(1, 1, 1), Clock.systemUTC());
        RecordingChain chain = new RecordingChain();

        StepVerifier.create(filter.filter(exchange(null), chain)).verifyComplete();

        assertThat(chain.invoked).isTrue();
    }

    @Test
    @DisplayName("disabling the feature stops limiting entirely")
    void respectsTheMasterSwitch() {
        RateLimitProperties.Budget budget = new RateLimitProperties.Budget(1, 1, 1);
        ApiKeyRateLimitWebFilter filter = new ApiKeyRateLimitWebFilter(redisTemplate, script(),
                new RateLimitProperties(false, budget, budget), errorWriter, meterRegistry, Clock.systemUTC());
        ApiKeyVerifyResult context = context(null, null, null);

        for (int i = 0; i < 5; i++) {
            RecordingChain chain = new RecordingChain();
            StepVerifier.create(filter.filter(exchange(context), chain)).verifyComplete();
            assertThat(chain.invoked).isTrue();
        }
    }

    @Test
    @DisplayName("an unreachable Redis fails open rather than refusing every request")
    void failsOpenWhenRedisIsUnavailable() {
        // Deliberate: rate limiting guards against excess load, so refusing everything because
        // the limiter is unreachable converts a capacity safeguard into a total outage — a
        // strictly worse failure than briefly serving unlimited traffic.
        LettuceConnectionFactory dead = new LettuceConnectionFactory("127.0.0.1", 1);
        dead.afterPropertiesSet();
        ApiKeyRateLimitWebFilter filter = new ApiKeyRateLimitWebFilter(
                new ReactiveStringRedisTemplate(dead), script(), properties(1, 1, 1),
                errorWriter, meterRegistry, Clock.systemUTC());

        RecordingChain chain = new RecordingChain();
        StepVerifier.create(filter.filter(exchange(context(null, null, null)), chain)).verifyComplete();

        assertThat(chain.invoked).as("the request must still be served").isTrue();
        assertThat(meterRegistry.counter("api_key_rate_limit_total", "outcome", "error").count()).isPositive();
        dead.destroy();
    }
}
