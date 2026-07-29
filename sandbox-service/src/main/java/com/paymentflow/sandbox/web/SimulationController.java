package com.paymentflow.sandbox.web;

import com.paymentflow.common.exception.ResourceNotFoundException;
import com.paymentflow.common.exception.UnauthorizedException;
import com.paymentflow.common.security.MerchantContext;
import com.paymentflow.common.security.MerchantContextHolder;
import com.paymentflow.sandbox.domain.SimulationOverride;
import com.paymentflow.sandbox.dto.CreateSimulationOverrideRequest;
import com.paymentflow.sandbox.dto.SimulationOverrideResponse;
import com.paymentflow.sandbox.mapper.SimulationOverrideMapper;
import com.paymentflow.sandbox.service.OverrideService;
import com.paymentflow.common.dto.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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

    /**
     * Declared with its description in {@code OpenApiConfig}. Set per operation rather
     * than as a class-level {@code @Tag}, which springdoc adds to every operation instead
     * of treating as an overridable default (M21.1).
     */
    static final String SIMULATIONS_TAG = "Simulations";

    public SimulationController(OverrideService overrideService, SimulationOverrideMapper mapper) {
        this.overrideService = overrideService;
        this.mapper = mapper;
    }

    @PostMapping
    @Operation(tags = SIMULATIONS_TAG, operationId = "createSimulationOverride",
            summary = "Force a sandbox behaviour",
            description = """
                    Installs an override that makes the sandbox behave a given way for your \
                    next authorizations, regardless of which test card they use.

                    Test cards cover the outcomes you can choose per payment; an override \
                    covers the ones you cannot — a processor outage, a sudden run of \
                    declines, latency that trips your timeouts — without needing a token for \
                    every combination. Bound it with `remainingCount` or `durationSeconds` so \
                    it stops on its own.

                    One override is active per merchant and mode at a time; creating another \
                    replaces it.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The override is active."),
            @ApiResponse(responseCode = "400", description = """
                    The override is not internally consistent — a `FORCE_DECLINE` with no \
                    `declineCode`, or neither `remainingCount` nor `durationSeconds` to end \
                    it.""",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(ref = ApiError.SCHEMA_REF)))})
    public ResponseEntity<SimulationOverrideResponse> create(@Valid @RequestBody CreateSimulationOverrideRequest request) {
        MerchantContext context = requireContext();
        SimulationOverride override = overrideService.create(context.merchantId(), context.mode(), request.scenario(),
                request.declineCode(), request.errorCode(), request.latencyMs(), request.remainingCount(),
                request.durationSeconds());
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toResponse(override));
    }

    @GetMapping("/active")
    @Operation(tags = SIMULATIONS_TAG, operationId = "getActiveSimulationOverride",
            summary = "Retrieve the active override",
            description = """
                    Returns the override currently in force for your merchant and mode, \
                    including how much of it is left. The first thing to check when the \
                    sandbox is behaving in a way you did not ask for — an override with \
                    `remainingCount` left over from an earlier test is the usual cause.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The active override."),
            @ApiResponse(responseCode = "404", description = "No override is active — the "
                    + "sandbox is behaving normally.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(ref = ApiError.SCHEMA_REF)))})
    public SimulationOverrideResponse getActive() {
        MerchantContext context = requireContext();
        // Throwing rather than `ResponseEntity.notFound().build()`, which is what this
        // returned until M21.7's contract test caught it: a **bodiless** 404, on a platform
        // whose error contract is that every non-2xx carries a catalogued code, a message and
        // a requestId assembled in one place (M21.4). It was the only response in the public
        // tier that returned nothing, and the document — which says 404 carries an ApiError,
        // like every other 404 — was right while the code was wrong.
        return overrideService.findActive(context.merchantId(), context.mode())
                .map(mapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No simulation override is active for this merchant and mode."));
    }

    @DeleteMapping("/active")
    @Operation(tags = SIMULATIONS_TAG, operationId = "revokeActiveSimulationOverride",
            summary = "Revoke the active override",
            description = """
                    Removes the override in force for your merchant and mode, returning the \
                    sandbox to its normal behaviour. Succeeds whether or not one was \
                    active — the point is the resulting state, so this is safe to call \
                    unconditionally at the start of a test.""")
    @ApiResponse(responseCode = "204", description = "No override is in force. Returned "
            + "whether or not one was.")
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
