package com.paymentflow.payment.merchant;

import com.paymentflow.common.security.MerchantContext;
import com.paymentflow.common.security.MerchantContextHolder;
import com.paymentflow.payment.exception.MerchantNotOnboardedException;
import com.paymentflow.payment.exception.MerchantServiceUnavailableException;
import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadConfig;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Fast, Spring-context-free tests of the actual Resilience4j wiring in
 * {@link MerchantResolver} (M8): built against real registries/instances (not mocks —
 * a mocked {@code CircuitBreaker} couldn't prove the composition order or the state
 * machine actually behaves correctly), with only {@link MerchantClient} mocked.
 */
class MerchantResolverTest {

    /** Something the test config records/retries as transient — deliberately not a Feign type. */
    private static class TransientFailure extends RuntimeException {
    }

    private ScheduledExecutorService scheduledExecutorService;

    @AfterEach
    void shutdownExecutor() {
        if (scheduledExecutorService != null) {
            scheduledExecutorService.shutdownNow();
        }
    }

    private MerchantResolver resolver(MerchantClient client, CircuitBreakerConfig cbConfig, RetryConfig retryConfig,
                                      ThreadPoolBulkheadConfig bulkheadConfig, TimeLimiterConfig timeLimiterConfig) {
        scheduledExecutorService = Executors.newScheduledThreadPool(4);
        return new MerchantResolver(client,
                CircuitBreakerRegistry.of(cbConfig),
                RetryRegistry.of(retryConfig),
                ThreadPoolBulkheadRegistry.of(bulkheadConfig),
                TimeLimiterRegistry.of(timeLimiterConfig),
                scheduledExecutorService);
    }

    private static CircuitBreakerConfig defaultCbConfig() {
        return CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMillis(200))
                .permittedNumberOfCallsInHalfOpenState(2)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordExceptions(TransientFailure.class)
                .ignoreExceptions(MerchantNotOnboardedException.class)
                .build();
    }

    private static RetryConfig defaultRetryConfig(int maxAttempts) {
        return RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .waitDuration(Duration.ofMillis(5))
                .retryExceptions(TransientFailure.class)
                .ignoreExceptions(MerchantNotOnboardedException.class)
                .build();
    }

    private static ThreadPoolBulkheadConfig defaultBulkheadConfig() {
        return ThreadPoolBulkheadConfig.custom()
                .coreThreadPoolSize(5)
                .maxThreadPoolSize(10)
                .queueCapacity(10)
                .keepAliveDuration(Duration.ofMillis(20))
                .build();
    }

    private static TimeLimiterConfig defaultTimeLimiterConfig() {
        return TimeLimiterConfig.custom().timeoutDuration(Duration.ofMillis(500)).cancelRunningFuture(true).build();
    }

    private static MerchantSummary summary() {
        return new MerchantSummary(UUID.randomUUID(), "billing@acme.test", null);
    }

    @Test
    void whenAnInternalMerchantContextIsPresentTheFeignClientIsNeverInvoked() {
        MerchantClient client = mock(MerchantClient.class);
        MerchantResolver resolver = resolver(client, defaultCbConfig(), defaultRetryConfig(3),
                defaultBulkheadConfig(), defaultTimeLimiterConfig());
        UUID merchantId = UUID.randomUUID();
        MerchantContext context = new MerchantContext(merchantId, "test", UUID.randomUUID(),
                java.util.Set.of("payments:write"), "billing@acme.test", "https://acme.test/hooks");

        try {
            MerchantContextHolder.set(context);
            MerchantSummary result = resolver.resolveCallerMerchant();

            assertThat(result).isEqualTo(new MerchantSummary(merchantId, "billing@acme.test", "https://acme.test/hooks"));
            verifyNoInteractions(client);
        } finally {
            MerchantContextHolder.clear();
        }
    }

    @Test
    void notOnboardedPassesThroughWithoutRetryOrCircuitImpact() {
        MerchantClient client = mock(MerchantClient.class);
        when(client.getMine()).thenThrow(notFound());
        MerchantResolver resolver = resolver(client, defaultCbConfig(), defaultRetryConfig(3),
                defaultBulkheadConfig(), defaultTimeLimiterConfig());

        assertThatThrownBy(resolver::resolveCallerMerchant).isInstanceOf(MerchantNotOnboardedException.class);

        verify(client, times(1)).getMine();
    }

    @Test
    void notOnboardedRepeatedlyNeverOpensTheCircuit() {
        MerchantClient client = mock(MerchantClient.class);
        when(client.getMine()).thenThrow(notFound());
        CircuitBreakerRegistry cbRegistry = CircuitBreakerRegistry.of(defaultCbConfig());
        scheduledExecutorService = Executors.newScheduledThreadPool(4);
        MerchantResolver resolver = new MerchantResolver(client, cbRegistry, RetryRegistry.of(defaultRetryConfig(3)),
                ThreadPoolBulkheadRegistry.of(defaultBulkheadConfig()), TimeLimiterRegistry.of(defaultTimeLimiterConfig()),
                scheduledExecutorService);

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(resolver::resolveCallerMerchant).isInstanceOf(MerchantNotOnboardedException.class);
        }

        assertThat(cbRegistry.circuitBreaker("merchantService").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void transientFailureIsRetriedThenSucceeds() {
        MerchantClient client = mock(MerchantClient.class);
        MerchantSummary summary = summary();
        when(client.getMine())
                .thenThrow(new TransientFailure())
                .thenThrow(new TransientFailure())
                .thenReturn(summary);
        MerchantResolver resolver = resolver(client, defaultCbConfig(), defaultRetryConfig(3),
                defaultBulkheadConfig(), defaultTimeLimiterConfig());

        MerchantSummary result = resolver.resolveCallerMerchant();

        assertThat(result).isEqualTo(summary);
        verify(client, times(3)).getMine();
    }

    @Test
    void retriesExhaustedSurfaceAsServiceUnavailable() {
        MerchantClient client = mock(MerchantClient.class);
        when(client.getMine()).thenThrow(new TransientFailure());
        MerchantResolver resolver = resolver(client, defaultCbConfig(), defaultRetryConfig(3),
                defaultBulkheadConfig(), defaultTimeLimiterConfig());

        assertThatThrownBy(resolver::resolveCallerMerchant).isInstanceOf(MerchantServiceUnavailableException.class);

        verify(client, times(3)).getMine();
    }

    @Test
    void circuitOpensAfterFailureThresholdAndFailsFastWithoutCallingTheClientAgain() {
        MerchantClient client = mock(MerchantClient.class);
        when(client.getMine()).thenThrow(new TransientFailure());
        CircuitBreakerRegistry cbRegistry = CircuitBreakerRegistry.of(defaultCbConfig());
        // maxAttempts=1: isolates "how many calls does it take to open the circuit"
        // from retry's own multiplying effect on call count.
        scheduledExecutorService = Executors.newScheduledThreadPool(4);
        MerchantResolver resolver = new MerchantResolver(client, cbRegistry, RetryRegistry.of(defaultRetryConfig(1)),
                ThreadPoolBulkheadRegistry.of(defaultBulkheadConfig()), TimeLimiterRegistry.of(defaultTimeLimiterConfig()),
                scheduledExecutorService);

        // minimumNumberOfCalls=4, slidingWindowSize=4, failureRateThreshold=50%: 4 failures trips it.
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(resolver::resolveCallerMerchant).isInstanceOf(MerchantServiceUnavailableException.class);
        }
        assertThat(cbRegistry.circuitBreaker("merchantService").getState()).isEqualTo(CircuitBreaker.State.OPEN);

        Mockito.clearInvocations(client);
        assertThatThrownBy(resolver::resolveCallerMerchant).isInstanceOf(MerchantServiceUnavailableException.class);

        verify(client, times(0)).getMine();
    }

    @Test
    void circuitTransitionsThroughHalfOpenBackToClosedOnceTheDownstreamRecovers() throws Exception {
        MerchantClient client = mock(MerchantClient.class);
        AtomicInteger callCount = new AtomicInteger();
        MerchantSummary summary = summary();
        when(client.getMine()).thenAnswer(inv -> {
            // First 4 calls fail (opens the circuit); everything after recovers.
            if (callCount.incrementAndGet() <= 4) {
                throw new TransientFailure();
            }
            return summary;
        });
        CircuitBreakerRegistry cbRegistry = CircuitBreakerRegistry.of(defaultCbConfig());
        scheduledExecutorService = Executors.newScheduledThreadPool(4);
        MerchantResolver resolver = new MerchantResolver(client, cbRegistry, RetryRegistry.of(defaultRetryConfig(1)),
                ThreadPoolBulkheadRegistry.of(defaultBulkheadConfig()), TimeLimiterRegistry.of(defaultTimeLimiterConfig()),
                scheduledExecutorService);

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(resolver::resolveCallerMerchant).isInstanceOf(MerchantServiceUnavailableException.class);
        }
        CircuitBreaker circuitBreaker = cbRegistry.circuitBreaker("merchantService");
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // automaticTransitionFromOpenToHalfOpenEnabled flips the state on Resilience4j's
        // own internal scheduler once waitDurationInOpenState (200ms) elapses — that
        // scheduler firing isn't guaranteed at exactly 200ms, so poll the real state
        // instead of sleeping past a guessed margin.
        await().atMost(Duration.ofSeconds(2)).until(() -> circuitBreaker.getState() == CircuitBreaker.State.HALF_OPEN);

        // permittedNumberOfCallsInHalfOpenState=2: two successful trial calls close it.
        assertThat(resolver.resolveCallerMerchant()).isEqualTo(summary);
        assertThat(resolver.resolveCallerMerchant()).isEqualTo(summary);

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void bulkheadRejectsCallsBeyondItsCapacity() throws Exception {
        MerchantClient client = mock(MerchantClient.class);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        when(client.getMine()).thenAnswer(inv -> {
            releaseLatch.await(2, TimeUnit.SECONDS);
            return summary();
        });
        ThreadPoolBulkheadConfig tinyBulkhead = ThreadPoolBulkheadConfig.custom()
                .coreThreadPoolSize(1).maxThreadPoolSize(1).queueCapacity(0)
                .keepAliveDuration(Duration.ofMillis(20)).build();
        MerchantResolver resolver = resolver(client, defaultCbConfig(), defaultRetryConfig(1),
                tinyBulkhead, TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(3)).build());

        ExecutorService callers = Executors.newFixedThreadPool(3);
        try {
            var first = callers.submit(resolver::resolveCallerMerchant);
            // Give the first call time to actually occupy the sole bulkhead thread.
            Thread.sleep(100);
            var second = callers.submit(resolver::resolveCallerMerchant);

            assertThatThrownBy(second::get).hasCauseInstanceOf(MerchantServiceUnavailableException.class);

            releaseLatch.countDown();
            assertThat(first.get()).isNotNull();
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void timeLimiterFailsFastWhenTheDownstreamIsSlow() {
        MerchantClient client = mock(MerchantClient.class);
        when(client.getMine()).thenAnswer(inv -> {
            // Far longer than the 300ms TimeLimiter budget, so "returned before the
            // downstream would have" is unambiguous with a wide margin — the assertion
            // below can't be flaked by scheduling jitter the way a near-budget sleep could.
            Thread.sleep(5000);
            return summary();
        });
        MerchantResolver resolver = resolver(client, defaultCbConfig(), defaultRetryConfig(1), defaultBulkheadConfig(),
                TimeLimiterConfig.custom().timeoutDuration(Duration.ofMillis(300)).cancelRunningFuture(true).build());

        long start = System.nanoTime();
        assertThatThrownBy(resolver::resolveCallerMerchant).isInstanceOf(MerchantServiceUnavailableException.class);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        // Fails fast at ~300ms (single attempt); asserting < 1500ms is ~5x headroom over
        // the budget yet a full 3.5s below the 5s mock, so only a broken TimeLimiter fails.
        assertThat(elapsedMs).isLessThan(1500);
    }

    private static FeignException.NotFound notFound() {
        Request request = Request.create(Request.HttpMethod.GET, "/api/v1/merchants/me",
                java.util.Map.of(), (byte[]) null, java.nio.charset.StandardCharsets.UTF_8, new RequestTemplate());
        return new FeignException.NotFound("not found", request, null, null);
    }
}
