package com.paymentflow.analytics.web;

import com.paymentflow.analytics.dto.AnalyticsSummaryResponse;
import com.paymentflow.analytics.service.AnalyticsQueryService;
import com.paymentflow.common.exception.UnauthorizedException;
import com.paymentflow.common.security.MerchantContext;
import com.paymentflow.common.security.MerchantContextHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * The analytics query API (M19.6, §5/M19 task 5) — analytics-service's first HTTP surface
 * since M7.
 *
 * <p>One endpoint rather than the several {@code /v1/analytics/*} implies: volume,
 * success rate and totals are all derived from the same hourly buckets, so splitting them
 * across routes would mean three queries over the same rows for a dashboard that renders
 * them on one screen. The response carries both the totals and the series, so a caller
 * charts from one round trip.
 */
@RestController
public class AnalyticsController {

    /**
     * Declared with its description in {@code OpenApiConfig}, and set per operation rather
     * than class-level for the reason M21.1 recorded: springdoc <em>adds</em> a
     * class-level {@code @Tag} to every operation instead of treating it as an overridable
     * default.
     */
    static final String ANALYTICS_TAG = "Analytics";

    private final AnalyticsQueryService analyticsQueryService;

    public AnalyticsController(AnalyticsQueryService analyticsQueryService) {
        this.analyticsQueryService = analyticsQueryService;
    }

    @GetMapping("/v1/analytics/payments")
    @Operation(tags = ANALYTICS_TAG, operationId = "getPaymentAnalytics",
            summary = "Summarize payment activity",
            description = """
                    Returns totals and an hourly series for your payment activity over a \
                    window, in one round trip — a dashboard renders both on one screen, so \
                    splitting them across endpoints would mean two queries over the same rows.

                    `successRate` is computed here rather than left to you, because there is \
                    more than one defensible denominator and a platform that returns raw \
                    counters invites every client to pick a different one. The published \
                    definition is `authorized / (authorized + failed)`.

                    The hourly series begins where this platform started recording it; \
                    totals are complete, but a window reaching further back than the series \
                    will show fewer buckets than hours.""")
    @ApiResponse(responseCode = "200", description = "Totals for the window, and the hourly "
            + "series behind them.")
    public AnalyticsSummaryResponse payments(
            @Parameter(description = "The start of the window, as RFC 3339. Defaults to 24 "
                    + "hours ago.", example = "2026-07-28T00:00:00Z")
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "The end of the window, as RFC 3339. Defaults to now.",
                    example = "2026-07-29T00:00:00Z")
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {

        MerchantContext context = requireContext();
        return analyticsQueryService.summary(context.merchantId(), context.mode(), from, to);
    }

    private static MerchantContext requireContext() {
        return MerchantContextHolder.get()
                .orElseThrow(() -> new UnauthorizedException("A verified internal context is required."));
    }
}
