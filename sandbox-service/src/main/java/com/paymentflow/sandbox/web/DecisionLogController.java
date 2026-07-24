package com.paymentflow.sandbox.web;

import com.paymentflow.common.dto.page.PageResponse;
import com.paymentflow.common.exception.UnauthorizedException;
import com.paymentflow.common.security.MerchantContext;
import com.paymentflow.common.security.MerchantContextHolder;
import com.paymentflow.sandbox.dto.DecisionLogEntryResponse;
import com.paymentflow.sandbox.mapper.DecisionLogMapper;
import com.paymentflow.sandbox.service.DecisionLogQueryService;
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

    public DecisionLogController(DecisionLogQueryService decisionLogQueryService, DecisionLogMapper mapper) {
        this.decisionLogQueryService = decisionLogQueryService;
        this.mapper = mapper;
    }

    @GetMapping
    public PageResponse<DecisionLogEntryResponse> list(Pageable pageable) {
        MerchantContext context = requireContext();
        Page<DecisionLogEntryResponse> page = decisionLogQueryService
                .list(context.merchantId(), context.mode(), pageable)
                .map(mapper::toResponse);
        return PageResponse.of(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements());
    }

    @GetMapping("/payments/{paymentId}")
    public List<DecisionLogEntryResponse> forPayment(@PathVariable UUID paymentId) {
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
