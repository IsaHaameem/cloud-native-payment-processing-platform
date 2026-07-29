package com.paymentflow.sandbox.web;

import com.paymentflow.common.dto.page.PageResponse;
import com.paymentflow.common.exception.UnauthorizedException;
import com.paymentflow.common.security.MerchantContext;
import com.paymentflow.common.security.MerchantContextHolder;
import com.paymentflow.sandbox.dto.DecisionLogEntryResponse;
import com.paymentflow.sandbox.mapper.DecisionLogMapper;
import com.paymentflow.sandbox.service.DecisionLogQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Public, key-authenticated decision-log query API (§4.2, M17.8) — "payment-service
 * learns the verdict; sandbox keeps the reasoning," now with a real caller for that
 * reasoning. Reachable only via the gateway's API-key path, the same signed
 * internal-context mechanism {@code SimulationController} (M17.5) already uses; no new
 * gateway route pattern needed beyond what a future one would add for this path
 * specifically (see the gateway wiring note in this milestone's changelog entry).
 *
 * <p>{@code merchantId}/{@code mode} come from the verified {@link MerchantContext},
 * never a path/query parameter — a merchant can only ever see their own decisions,
 * scoped to the mode they're authenticated in (§7).
 */
@RestController
@RequestMapping("/v1/test/decisions")
public class DecisionLogController {

    private final DecisionLogQueryService decisionLogQueryService;
    private final DecisionLogMapper mapper;

    /**
     * Declared with its description in {@code OpenApiConfig}. Set per operation rather
     * than as a class-level {@code @Tag}, which springdoc adds to every operation instead
     * of treating as an overridable default (M21.1).
     */
    static final String DECISIONS_TAG = "Decisions";

    public DecisionLogController(DecisionLogQueryService decisionLogQueryService, DecisionLogMapper mapper) {
        this.decisionLogQueryService = decisionLogQueryService;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(tags = DECISIONS_TAG, operationId = "listSandboxDecisions",
            summary = "List sandbox decisions",
            description = """
                    Returns every verdict the simulated acquirer has reached for you, most \
                    recent first — and, in `source`, **why**: the test card's own catalogue \
                    entry, an active simulation override, or the mode's default.

                    payment-service learns the verdict; the sandbox keeps the reasoning. This \
                    is where to look when a payment declined and you expected it not to.

                    Offset-paginated with `page` and `size` rather than the cursor \
                    pagination the rest of the public API uses. That is deliberate and \
                    documented (D139): this endpoint predates the cursor contract and was not \
                    retrofitted, because doing so would break callers to no benefit on a list \
                    nobody paginates deeply.""")
    @ApiResponse(responseCode = "200", description = "A page of decisions, most recent first.")
    // M21.2: see WebhookDeliveryController.list — without @ParameterObject springdoc
    // publishes the `Pageable` binding type itself as a required `pageable` object
    // parameter that does not exist on the wire.
    public PageResponse<DecisionLogEntryResponse> list(@ParameterObject Pageable pageable) {
        MerchantContext context = requireContext();
        Page<DecisionLogEntryResponse> page = decisionLogQueryService
                .list(context.merchantId(), context.mode(), pageable)
                .map(mapper::toResponse);
        return PageResponse.of(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements());
    }

    @GetMapping("/payments/{paymentId}")
    @Operation(tags = DECISIONS_TAG, operationId = "listSandboxDecisionsForPayment",
            summary = "List the decisions for one payment",
            description = """
                    Returns every decision made about a single payment, in the order they \
                    were made — the authorization, then any capture or refund — so a \
                    payment's whole sandbox history reads as one sequence.

                    A payment with no decisions returns an empty list rather than a `404`: \
                    "this payment was never authorized" is an answer, not a missing \
                    resource.""")
    @ApiResponse(responseCode = "200", description = "The decisions for this payment, oldest "
            + "first. Empty when none were made.")
    public List<DecisionLogEntryResponse> forPayment(
            @Parameter(description = "The payment whose decisions to return.")
            @PathVariable UUID paymentId) {
        MerchantContext context = requireContext();
        return decisionLogQueryService.forPayment(context.merchantId(), context.mode(), paymentId).stream()
                .map(mapper::toResponse)
                .toList();
    }

    private static MerchantContext requireContext() {
        return MerchantContextHolder.get()
                .orElseThrow(() -> new UnauthorizedException("A verified internal context is required."));
    }
}
