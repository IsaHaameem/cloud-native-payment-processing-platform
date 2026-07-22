package com.paymentflow.gateway.security.apikey;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.reactor.timelimiter.TimeLimiterOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Wraps {@link ApiKeyVerificationClient} in the same Retry → CircuitBreaker →
 * TimeLimiter shape M8's {@code MerchantResolver} established (D49) — composed here
 * via resilience4j-reactor's {@code Mono} operators instead of the
 * {@code CompletionStage} decorator chain, since the gateway is reactive end to end
 * and has no blocking call/thread pool to isolate (no {@code ThreadPoolBulkhead}
 * needed — {@link org.springframework.web.reactive.function.client.WebClient} is
 * already non-blocking). Applied innermost-to-outermost via
 * {@code transformDeferred}: TimeLimiter closest to the call, CircuitBreaker around
 * that, Retry outermost — the same nesting order D49 specifies.
 */
@Component
public class ResilientApiKeyVerifier {

    private static final String INSTANCE_NAME = "apiKeyVerify";

    private final ApiKeyVerificationClient client;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final TimeLimiter timeLimiter;

    public ResilientApiKeyVerifier(ApiKeyVerificationClient client, CircuitBreakerRegistry circuitBreakerRegistry,
                                   RetryRegistry retryRegistry, TimeLimiterRegistry timeLimiterRegistry) {
        this.client = client;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(INSTANCE_NAME);
        this.retry = retryRegistry.retry(INSTANCE_NAME);
        this.timeLimiter = timeLimiterRegistry.timeLimiter(INSTANCE_NAME);
    }

    public Mono<ApiKeyVerifyResult> verify(String rawKey) {
        return client.verify(rawKey)
                .transformDeferred(TimeLimiterOperator.of(timeLimiter))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(RetryOperator.of(retry));
    }
}
