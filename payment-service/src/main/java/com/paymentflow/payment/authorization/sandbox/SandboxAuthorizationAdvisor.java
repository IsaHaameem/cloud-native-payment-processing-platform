package com.paymentflow.payment.authorization.sandbox;

import com.paymentflow.common.security.InternalContextProperties;
import com.paymentflow.common.security.InternalContextSigner;
import com.paymentflow.payment.authorization.AuthorizationAdvisor;
import com.paymentflow.payment.authorization.AuthorizationDecision;
import com.paymentflow.payment.authorization.AuthorizationRequest;
import com.paymentflow.payment.exception.SandboxServiceUnavailableException;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

/**
 * The one adapter behind {@link AuthorizationAdvisor} (D132): calls sandbox-service's
 * internal decision endpoint over the same Retry → CircuitBreaker → TimeLimiter →
 * ThreadPoolBulkhead decorator chain {@code MerchantResolver} established (M8, D49),
 * composed programmatically against the Spring-managed registries for the same reason
 * (sidesteps {@code spring-boot-starter-aop}/{@code @Order} entirely). {@link
 * ThreadPoolBulkhead} runs the call on its own dedicated pool so a hung sandbox-service
 * can only ever saturate that small pool, never payment-service's request-handling
 * threads — the call is obtained before {@code PaymentService} opens its transaction
 * (D129), so a slow or down sandbox-service never holds a pooled DB connection idle.
 *
 * <p>Unlike {@code MerchantResolver}, no {@code RequestAttributes} hand-off is needed:
 * the internal-context headers this call sends are computed synchronously from {@code
 * request} before the resilience chain ever dispatches (see {@link #callSandbox}), not
 * forwarded from the original caller's own request — there is no thread-affinity
 * requirement to work around.
 *
 * <p>Every sandbox-specific concept — {@code source}, {@code latencyMs}, the raw
 * {@code REQUIRE_ACTION} outcome, the decision key — is translated or discarded in
 * {@link #toDecision}; nothing beyond {@link AuthorizationDecision} ever reaches
 * {@code PaymentService} (D132).
 */
@Component
public class SandboxAuthorizationAdvisor implements AuthorizationAdvisor {

    private static final String INSTANCE_NAME = "sandboxService";
    private static final String OPERATION_AUTHORIZE = "AUTHORIZE";

    /**
     * No neutral {@link com.paymentflow.payment.authorization.AuthorizationOutcome}
     * exists for "requires further customer action" — no step-up-auth flow exists
     * anywhere in V2 (§15). Folded into DECLINED with a distinct code rather than
     * silently approved or misreported as a processing error (decision confirmed
     * 2026-07-24: fold into DECLINED rather than add a fifth outcome, since {@code
     * PaymentService} has no FSM state to do anything else with it in M17.4 either way).
     */
    private static final String DECLINE_CODE_AUTHENTICATION_REQUIRED = "authentication_required";

    private final SandboxClient sandboxClient;
    private final InternalContextSigner signer;
    private final InternalContextProperties internalContextProperties;
    private final SandboxResilienceProperties sandboxProperties;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final ThreadPoolBulkhead bulkhead;
    private final TimeLimiter timeLimiter;
    private final ScheduledExecutorService scheduledExecutorService;

    public SandboxAuthorizationAdvisor(SandboxClient sandboxClient, InternalContextSigner signer,
                                       InternalContextProperties internalContextProperties,
                                       SandboxResilienceProperties sandboxProperties,
                                       CircuitBreakerRegistry circuitBreakerRegistry, RetryRegistry retryRegistry,
                                       ThreadPoolBulkheadRegistry threadPoolBulkheadRegistry,
                                       TimeLimiterRegistry timeLimiterRegistry,
                                       ScheduledExecutorService scheduledExecutorService) {
        this.sandboxClient = sandboxClient;
        this.signer = signer;
        this.internalContextProperties = internalContextProperties;
        this.sandboxProperties = sandboxProperties;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(INSTANCE_NAME);
        this.retry = retryRegistry.retry(INSTANCE_NAME);
        this.bulkhead = threadPoolBulkheadRegistry.bulkhead(INSTANCE_NAME);
        this.timeLimiter = timeLimiterRegistry.timeLimiter(INSTANCE_NAME);
        this.scheduledExecutorService = scheduledExecutorService;
    }

    @Override
    public AuthorizationDecision advise(AuthorizationRequest request) {
        Supplier<CompletionStage<SandboxDecisionResponse>> bulkheadProtected =
                () -> bulkhead.executeSupplier(() -> callSandbox(request));
        Supplier<CompletionStage<SandboxDecisionResponse>> timeLimited =
                TimeLimiter.decorateCompletionStage(timeLimiter, scheduledExecutorService, bulkheadProtected);
        Supplier<CompletionStage<SandboxDecisionResponse>> circuitProtected =
                CircuitBreaker.decorateCompletionStage(circuitBreaker, timeLimited);
        Supplier<CompletionStage<SandboxDecisionResponse>> resilientCall =
                Retry.decorateCompletionStage(retry, scheduledExecutorService, circuitProtected);

        try {
            return toDecision(resilientCall.get().toCompletableFuture().get());
        } catch (ExecutionException e) {
            throw new SandboxServiceUnavailableException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SandboxServiceUnavailableException(e);
        }
    }

    private SandboxDecisionResponse callSandbox(AuthorizationRequest request) {
        long issuedAtEpochSecond = Instant.now().getEpochSecond();
        String merchantId = request.merchantId().toString();
        String signature = signer.sign(internalContextProperties.secret(), merchantId, request.mode(),
                sandboxProperties.serviceKeyId(), sandboxProperties.serviceScopes(), null, null, issuedAtEpochSecond);

        // Scoped to (paymentId, operation) — not this attempt's own idempotency key
        // (D128): a payment can only ever be authorized once (the FSM forbids
        // CREATED -> AUTHORIZED twice), so every attempt, including a client retry
        // under a brand-new Idempotency-Key, must resolve to the SAME sandbox
        // decision, never a fresh roll of a stochastic acquirer (D129, M17.7).
        String decisionKey = request.paymentId() + ":" + OPERATION_AUTHORIZE;
        SandboxDecisionRequest sandboxRequest = new SandboxDecisionRequest(decisionKey, request.paymentId(),
                OPERATION_AUTHORIZE, request.paymentMethodToken(), request.amountMinor(), request.currency());

        return sandboxClient.decide(merchantId, request.mode(), sandboxProperties.serviceKeyId(),
                sandboxProperties.serviceScopes(), Long.toString(issuedAtEpochSecond), signature, sandboxRequest);
    }

    private static AuthorizationDecision toDecision(SandboxDecisionResponse response) {
        return switch (response.outcome()) {
            case "APPROVE" -> AuthorizationDecision.approved();
            case "DECLINE" -> AuthorizationDecision.declined(response.declineCode());
            case "ERROR" -> AuthorizationDecision.error(response.errorCode());
            case "REQUIRE_ACTION" -> AuthorizationDecision.declined(DECLINE_CODE_AUTHENTICATION_REQUIRED);
            default -> throw new IllegalStateException("Unrecognised sandbox decision outcome: " + response.outcome());
        };
    }
}
