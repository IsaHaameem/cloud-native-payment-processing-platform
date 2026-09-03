package com.paymentflow.agentic.web;

import com.paymentflow.agentic.checkout.CheckoutRepository;
import com.paymentflow.agentic.checkout.CheckoutService;
import com.paymentflow.agentic.checkout.CheckoutView;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The merchant-facing read view of checkouts (G-2).
 *
 * <pre>
 *   GET /api/agentic/checkouts?page=&limit=   the list, newest first
 *   GET /api/agentic/checkouts/{id}           one checkout, with its priced lines
 * </pre>
 *
 * <h2>Read-only, and totals are server-derived</h2>
 *
 * <p>A checkout is the amount a payment will charge; the only actor that creates or changes one
 * is the agent, through the guarded {@code create_checkout} tool, and the total is computed by
 * {@code CheckoutService} from catalogue prices — never supplied by a caller. This controller
 * exposes what those checkouts are; it grants no way to set a total, a merchant, a mode, or a
 * payment link. Reading {@code /{id}} through {@link CheckoutService#require} also lazily expires
 * a checkout whose TTL has passed, so the state a merchant sees is current.
 */
@RestController
@RequestMapping("/api/agentic/checkouts")
public class CheckoutController {

    private final CheckoutService checkoutService;
    private final CheckoutRepository checkoutRepository;
    private final AgenticCallerContext callerContext;

    public CheckoutController(CheckoutService checkoutService, CheckoutRepository checkoutRepository,
                             AgenticCallerContext callerContext) {
        this.checkoutService = checkoutService;
        this.checkoutRepository = checkoutRepository;
        this.callerContext = callerContext;
    }

    @GetMapping
    public PageResponse<CheckoutView> list(@RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer limit) {
        AgenticCallerContext.Caller caller = callerContext.resolve();
        int clampedPage = PageResponse.clampPage(page);
        int clampedLimit = PageResponse.clampLimit(limit);

        var rows = checkoutRepository.findByMerchantIdAndModeOrderByCreatedAtDesc(
                caller.merchantId(), caller.mode(), PageRequest.of(clampedPage, clampedLimit));
        long total = checkoutRepository.countByMerchantIdAndMode(caller.merchantId(), caller.mode());
        return PageResponse.of(rows, clampedPage, clampedLimit, total, CheckoutView::of);
    }

    @GetMapping("/{id}")
    public CheckoutView get(@PathVariable UUID id) {
        AgenticCallerContext.Caller caller = callerContext.resolve();
        return CheckoutView.of(checkoutService.require(caller.merchantId(), caller.mode(), id));
    }
}
