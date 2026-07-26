package com.paymentflow.analytics.web;

import com.paymentflow.analytics.dto.AnalyticsSummaryResponse;
import com.paymentflow.analytics.service.AnalyticsQueryService;
import com.paymentflow.common.exception.UnauthorizedException;
import com.paymentflow.common.security.MerchantContext;
import com.paymentflow.common.security.MerchantContextHolder;
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

    private final AnalyticsQueryService analyticsQueryService;

    public AnalyticsController(AnalyticsQueryService analyticsQueryService) {
        this.analyticsQueryService = analyticsQueryService;
    }

    @GetMapping("/v1/analytics/payments")
    public AnalyticsSummaryResponse payments(
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
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
