package com.paymentflow.agentic.web;

import com.paymentflow.agentic.provider.ProviderDecision;
import com.paymentflow.agentic.provider.ProviderDecisionRecord;
import com.paymentflow.agentic.provider.ProviderDecisionRepository;
import com.paymentflow.agentic.provider.ProviderOutcome;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The merchant-facing read view of persisted provider decisions (G-6).
 *
 * <pre>
 *   GET /api/agentic/provider-decisions?payment_id=      decisions for one payment
 *   GET /api/agentic/provider-decisions?page=&limit=     all of this merchant's, newest first
 * </pre>
 *
 * <h2>The distinction this endpoint exists to make legible</h2>
 *
 * <p>Each row carries a {@code kind}: {@code REAL_AUTHORIZATION} when a cardholder authorized the
 * payment and the provider reported it; {@code DEMO_ORDER_ACCEPTED} when Razorpay accepted an
 * order and <em>nobody authorized anything</em> — the demonstration stand-in that is only
 * reachable when an operator has set {@code razorpay.uncollected-order-outcome=approve}, which is
 * not the default. A UI must never render the second as a successful card payment; {@code kind}
 * and {@code demoApproval} are how it tells them apart without parsing {@code source}.
 */
@RestController
@RequestMapping("/api/agentic/provider-decisions")
public class ProviderDecisionQueryController {

    private final ProviderDecisionRepository decisionRepository;
    private final AgenticCallerContext callerContext;

    public ProviderDecisionQueryController(ProviderDecisionRepository decisionRepository,
                                           AgenticCallerContext callerContext) {
        this.decisionRepository = decisionRepository;
        this.callerContext = callerContext;
    }

    /** How a reader should treat this decision. Derived, so a UI never has to interpret {@code source}. */
    public enum Kind {
        /** A cardholder authorized the payment and the provider reported it. A real success. */
        REAL_AUTHORIZATION,
        /** Razorpay accepted an order; no cardholder authorized anything. Demonstration only. */
        DEMO_ORDER_ACCEPTED,
        /** The acquirer refused the payment. */
        DECLINED,
        /** The attempt failed for a reason that is not a verdict — unreachable or unusable. */
        ERRORED,
        /** No provider credential is configured, so nothing was asked. */
        NOT_CONFIGURED
    }

    public record ProviderDecisionView(
            String id,
            String paymentId,
            String operation,
            String outcome,
            Kind kind,
            boolean demoApproval,
            String source,
            String declineCode,
            String errorCode,
            String providerReference,
            String providerName,
            long amountMinor,
            String currency,
            String correlationId,
            Instant createdAt) {

        static ProviderDecisionView of(ProviderDecisionRecord record) {
            return new ProviderDecisionView(
                    record.getId() == null ? null : record.getId().toString(),
                    record.getPaymentId().toString(),
                    record.getOperation(),
                    record.getOutcome().name(),
                    kindOf(record),
                    record.isDemoApproval(),
                    record.getSource(),
                    record.getDeclineCode(),
                    record.getErrorCode(),
                    record.getProviderReference(),
                    record.getProviderName(),
                    record.getAmountMinor(),
                    record.getCurrency(),
                    record.getCorrelationId(),
                    record.getCreatedAt());
        }

        private static Kind kindOf(ProviderDecisionRecord record) {
            if (record.getOutcome() == ProviderOutcome.APPROVE) {
                return record.isDemoApproval() ? Kind.DEMO_ORDER_ACCEPTED : Kind.REAL_AUTHORIZATION;
            }
            if (record.getOutcome() == ProviderOutcome.DECLINE) {
                return Kind.DECLINED;
            }
            return ProviderDecision.SOURCE_NOT_CONFIGURED.equals(record.getSource())
                    ? Kind.NOT_CONFIGURED
                    : Kind.ERRORED;
        }
    }

    @GetMapping
    public PageResponse<ProviderDecisionView> list(
            @RequestParam(name = "payment_id", required = false) UUID paymentId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer limit) {
        AgenticCallerContext.Caller caller = callerContext.resolve();
        int clampedLimit = PageResponse.clampLimit(limit);

        if (paymentId != null) {
            List<ProviderDecisionView> rows = decisionRepository
                    .findByMerchantIdAndModeAndPaymentIdOrderByCreatedAtDesc(
                            caller.merchantId(), caller.mode(), paymentId)
                    .stream()
                    .map(ProviderDecisionView::of)
                    .toList();
            return new PageResponse<>(rows, 0, clampedLimit, rows.size(), false);
        }

        int clampedPage = PageResponse.clampPage(page);
        var rows = decisionRepository.findByMerchantIdAndModeOrderByCreatedAtDesc(
                caller.merchantId(), caller.mode(), PageRequest.of(clampedPage, clampedLimit));
        long total = decisionRepository.countByMerchantIdAndMode(caller.merchantId(), caller.mode());
        return PageResponse.of(rows, clampedPage, clampedLimit, total, ProviderDecisionView::of);
    }
}
