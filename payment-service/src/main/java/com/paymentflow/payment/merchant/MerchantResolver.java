package com.paymentflow.payment.merchant;

import com.paymentflow.payment.exception.MerchantNotOnboardedException;
import com.paymentflow.payment.exception.MerchantServiceUnavailableException;
import feign.FeignException;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

/**
 * Resolves the calling (JWT-authenticated) user's merchant profile via merchant-service,
 * wrapped in a Resilience4j Retry → CircuitBreaker → TimeLimiter → ThreadPoolBulkhead
 * decorator chain (M8) — the platform's only synchronous cross-service call (D32).
 *
 * <p>Composed programmatically against the Spring-managed registries via each
 * component's {@code CompletionStage} decorator (the only style all four compose
 * through cleanly — {@code ThreadPoolBulkhead} only ever returns a
 * {@code CompletionStage}, not a plain {@code Callable}/{@code Future}), rather than
 * via {@code @CircuitBreaker}/{@code @Retry}/... annotations (D49): the registries are
 * still auto-configured and Micrometer-bound identically either way, but this sidesteps
 * needing {@code spring-boot-starter-aop} and any {@code @Order} aspect-ordering
 * configuration to get the composition (outermost Retry, innermost
 * ThreadPoolBulkhead) actually correct.
 *
 * <p>{@link ThreadPoolBulkhead} runs the Feign call on its own dedicated pool, not the
 * calling Servlet thread — deliberately, so a hung merchant-service can only ever
 * saturate that small pool, never the application's main request-handling threads
 * (the actual "prevent one failing downstream service from exhausting all application
 * threads" requirement). That hand-off breaks {@link FeignAuthorizationForwardingConfig}'s
 * interceptor, which reads the caller's JWT off {@link RequestContextHolder} — a plain
 * (non-inheritable) {@code ThreadLocal} that a different thread simply doesn't see. The
 * caller's request attributes are captured before dispatch and explicitly propagated
 * onto (and cleared from) the bulkhead thread around the actual call — see
 * {@link #callOnCurrentThread(RequestAttributes)} — a real bug found and fixed during
 * this milestone (see Problems in the M8 changelog), not a hypothetical concern.
 */
@Component
public class MerchantResolver {

    private static final String INSTANCE_NAME = "merchantService";

    private final MerchantClient merchantClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final ThreadPoolBulkhead bulkhead;
    private final TimeLimiter timeLimiter;
    private final ScheduledExecutorService scheduledExecutorService;

    public MerchantResolver(MerchantClient merchantClient, CircuitBreakerRegistry circuitBreakerRegistry,
                            RetryRegistry retryRegistry, ThreadPoolBulkheadRegistry threadPoolBulkheadRegistry,
                            TimeLimiterRegistry timeLimiterRegistry, ScheduledExecutorService scheduledExecutorService) {
        this.merchantClient = merchantClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(INSTANCE_NAME);
        this.retry = retryRegistry.retry(INSTANCE_NAME);
        this.bulkhead = threadPoolBulkheadRegistry.bulkhead(INSTANCE_NAME);
        this.timeLimiter = timeLimiterRegistry.timeLimiter(INSTANCE_NAME);
        this.scheduledExecutorService = scheduledExecutorService;
    }

    public MerchantSummary resolveCallerMerchant() {
        RequestAttributes callerAttributes = RequestContextHolder.getRequestAttributes();

        Supplier<CompletionStage<MerchantSummary>> bulkheadProtected =
                () -> bulkhead.executeSupplier(() -> callOnCurrentThread(callerAttributes));
        Supplier<CompletionStage<MerchantSummary>> timeLimited =
                TimeLimiter.decorateCompletionStage(timeLimiter, scheduledExecutorService, bulkheadProtected);
        Supplier<CompletionStage<MerchantSummary>> circuitProtected =
                CircuitBreaker.decorateCompletionStage(circuitBreaker, timeLimited);
        Supplier<CompletionStage<MerchantSummary>> resilientCall =
                Retry.decorateCompletionStage(retry, scheduledExecutorService, circuitProtected);

        try {
            return resilientCall.get().toCompletableFuture().get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof MerchantNotOnboardedException notOnboarded) {
                throw notOnboarded;
            }
            throw new MerchantServiceUnavailableException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MerchantServiceUnavailableException(e);
        }
    }

    /**
     * Runs on the {@link ThreadPoolBulkhead}'s own pool thread, not the original
     * Servlet thread — restores whatever {@link RequestAttributes} were there before
     * (normally none) once done, so a reused pool thread never leaks one request's JWT
     * context into the next task it happens to run.
     */
    private MerchantSummary callOnCurrentThread(RequestAttributes callerAttributes) {
        RequestAttributes previous = RequestContextHolder.getRequestAttributes();
        RequestContextHolder.setRequestAttributes(callerAttributes);
        try {
            return callMerchantService();
        } finally {
            RequestContextHolder.setRequestAttributes(previous);
        }
    }

    private MerchantSummary callMerchantService() {
        try {
            return merchantClient.getMine();
        } catch (FeignException.NotFound e) {
            throw new MerchantNotOnboardedException();
        }
    }
}
