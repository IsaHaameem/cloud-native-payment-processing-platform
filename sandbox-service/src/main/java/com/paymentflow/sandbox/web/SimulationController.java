package com.paymentflow.sandbox.web;

import com.paymentflow.common.exception.UnauthorizedException;
import com.paymentflow.common.security.MerchantContext;
import com.paymentflow.common.security.MerchantContextHolder;
import com.paymentflow.sandbox.domain.SimulationOverride;
import com.paymentflow.sandbox.dto.CreateSimulationOverrideRequest;
import com.paymentflow.sandbox.dto.SimulationOverrideResponse;
import com.paymentflow.sandbox.mapper.SimulationOverrideMapper;
import com.paymentflow.sandbox.service.OverrideService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, key-authenticated simulation controls (§8.2, M17.5) — reachable only via the
 * gateway's API-key path (an {@code sk_test_} secret key), which asserts the same
 * HMAC-signed internal context as any other API-key request (D100). Unlike
 * {@code SandboxDecisionController}, these methods return plain values, not a
 * {@code CompletableFuture} — Spring Security's normal (non-async) request handling
 * applies, so this controller needs no {@code permitAll()} carve-out: the existing
 * {@code .anyRequest().authenticated()} catch-all in {@code SecurityConfig} already
 * covers it, satisfied the same way {@code InternalContextFilter} satisfies it for
 * every other authenticated sandbox-service request.
 *
 * <p>{@code merchantId}/{@code mode} come from the verified {@link MerchantContext},
 * never from the request body or a client header — the same §7 barrier ①
 * {@code SandboxDecisionController} enforces.
 */
@RestController
@RequestMapping("/v1/test/simulations")
public class SimulationController {

    private final OverrideService overrideService;
    private final SimulationOverrideMapper mapper;

    public SimulationController(OverrideService overrideService, SimulationOverrideMapper mapper) {
        this.overrideService = overrideService;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<SimulationOverrideResponse> create(@Valid @RequestBody CreateSimulationOverrideRequest request) {
        MerchantContext context = requireContext();
        SimulationOverride override = overrideService.create(context.merchantId(), context.mode(), request.scenario(),
                request.declineCode(), request.errorCode(), request.latencyMs(), request.remainingCount(),
                request.durationSeconds());
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toResponse(override));
    }

    @GetMapping("/active")
    public ResponseEntity<SimulationOverrideResponse> getActive() {
        MerchantContext context = requireContext();
        return overrideService.findActive(context.merchantId(), context.mode())
                .map(override -> ResponseEntity.ok(mapper.toResponse(override)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/active")
    public ResponseEntity<Void> revokeActive() {
        MerchantContext context = requireContext();
        overrideService.revokeActive(context.merchantId(), context.mode());
        return ResponseEntity.noContent().build();
    }

    private static MerchantContext requireContext() {
        return MerchantContextHolder.get()
                .orElseThrow(() -> new UnauthorizedException("A verified internal context is required."));
    }
}
