package com.paymentflow.analytics.web;

import com.paymentflow.analytics.dto.RequestLogResponse;
import com.paymentflow.analytics.dto.UsageSummaryResponse;
import com.paymentflow.analytics.service.RequestLogQueryService;
import com.paymentflow.common.dto.page.CursorPage;
import com.paymentflow.common.exception.UnauthorizedException;
import com.paymentflow.common.query.CursorCodec;
import com.paymentflow.common.query.ListQuery;
import com.paymentflow.common.security.MerchantContext;
import com.paymentflow.common.security.MerchantContextHolder;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;

/**
 * The developer-visible request log and usage APIs (M20.6, §5/M20 task 6).
 *
 * <p>This is the half of M20 that makes the rest of it useful: everything before this captured,
 * stored and aggregated requests that no developer could actually see.
 *
 * <p>Both endpoints take merchant and mode from the verified internal context, never from a
 * parameter (D28/D101), and both reuse M19's shared primitives — {@code ListQuery},
 * {@code CursorPage}, {@code CursorCodec} — so list semantics here are identical to payments,
 * refunds, events and the ledger rather than a sixth dialect.
 */
@RestController
public class RequestLogController {

    private final RequestLogQueryService requestLogQueryService;
    private final CursorCodec cursorCodec;

    public RequestLogController(RequestLogQueryService requestLogQueryService, CursorCodec cursorCodec) {
        this.requestLogQueryService = requestLogQueryService;
        this.cursorCodec = cursorCodec;
    }

    @GetMapping("/v1/request_logs")
    public CursorPage<RequestLogResponse> requestLogs(
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "starting_after", required = false) String startingAfter,
            @RequestParam(name = "created_after", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdAfter,
            @RequestParam(name = "created_before", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdBefore,
            @RequestParam(name = "status_code", required = false) Integer statusCode,
            @RequestParam(name = "method", required = false) String method) {

        MerchantContext context = requireContext();
        ListQuery query = ListQuery.resolve(limit, startingAfter, createdAfter, createdBefore,
                cursorCodec, context.merchantId(), context.mode());

        return requestLogQueryService.listRequestLogs(
                context.merchantId(), context.mode(), query, statusCode, method);
    }

    /**
     * Dated rather than timestamped, because the aggregate is per UTC day — accepting an
     * instant would imply a precision the stored data does not have.
     */
    @GetMapping("/v1/usage")
    public UsageSummaryResponse usage(
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        MerchantContext context = requireContext();
        return requestLogQueryService.usage(context.merchantId(), context.mode(), from, to);
    }

    private static MerchantContext requireContext() {
        return MerchantContextHolder.get()
                .orElseThrow(() -> new UnauthorizedException("A verified internal context is required."));
    }
}
