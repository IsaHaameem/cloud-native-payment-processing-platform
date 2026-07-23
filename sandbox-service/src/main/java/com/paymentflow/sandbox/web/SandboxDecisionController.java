package com.paymentflow.sandbox.web;

import com.paymentflow.common.correlation.CorrelationConstants;
import com.paymentflow.common.exception.UnauthorizedException;
import com.paymentflow.common.security.MerchantContext;
import com.paymentflow.common.security.MerchantContextHolder;
import com.paymentflow.sandbox.dto.SandboxDecisionRequest;
import com.paymentflow.sandbox.dto.SandboxDecisionResponse;
import com.paymentflow.sandbox.service.SandboxDecisionService;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Internal-only advisory endpoint (§3.2, D103) — never routed by the gateway, and
 * unreachable without a valid signed internal context (D100, verified by
 * {@code InternalContextFilter} before this controller ever runs). payment-service's
 * {@code SandboxAuthorizationAdvisor} adapter (M17.4) is this endpoint's only caller.
 *
 * <p>{@code merchantId}/{@code mode} come from the verified {@link MerchantContext},
 * never from the request body (§7 barrier ①) — the controller rejects the request
 * outright if no context was verified, rather than falling back to an unauthenticated
 * default.
 */
@RestController
@RequestMapping("/internal/v1/sandbox")
public class SandboxDecisionController {

    private final SandboxDecisionService sandboxDecisionService;
    private final ScheduledExecutorService sandboxDelayExecutor;

    public SandboxDecisionController(SandboxDecisionService sandboxDecisionService,
                                     ScheduledExecutorService sandboxDelayExecutor) {
        this.sandboxDecisionService = sandboxDecisionService;
        this.sandboxDelayExecutor = sandboxDelayExecutor;
    }

    @PostMapping("/decisions")
    public CompletableFuture<SandboxDecisionResponse> decide(@Valid @RequestBody SandboxDecisionRequest request) {
        MerchantContext context = MerchantContextHolder.get()
                .orElseThrow(() -> new UnauthorizedException("A verified internal context is required."));
        String correlationId = MDC.get(CorrelationConstants.CORRELATION_ID_MDC_KEY);

        SandboxDecisionResponse response =
                sandboxDecisionService.decide(context.merchantId(), context.mode(), request, correlationId);

        if (response.latencyMs() <= 0) {
            return CompletableFuture.completedFuture(response);
        }
        // The decision is already evaluated and durably recorded above — only the
        // response's delivery is delayed, non-blockingly (see SandboxAsyncConfig).
        return CompletableFuture.supplyAsync(() -> response,
                CompletableFuture.delayedExecutor(response.latencyMs(), TimeUnit.MILLISECONDS, sandboxDelayExecutor));
    }
}
