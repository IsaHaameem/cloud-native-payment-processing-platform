package com.paymentflow.analytics.web;

import com.paymentflow.analytics.dto.RequestLogResponse;
import com.paymentflow.analytics.dto.UsageSummaryResponse;
import com.paymentflow.analytics.service.RequestLogQueryService;
import com.paymentflow.common.dto.page.CursorPage;
import com.paymentflow.common.dto.error.ApiError;
import com.paymentflow.common.exception.UnauthorizedException;
import com.paymentflow.common.openapi.PublicApiParameters;
import com.paymentflow.common.query.CursorCodec;
import com.paymentflow.common.query.ListQuery;
import com.paymentflow.common.security.MerchantContext;
import com.paymentflow.common.security.MerchantContextHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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

    /**
     * Two tags on one controller, declared with their descriptions in
     * {@code OpenApiConfig}. This is exactly the case a class-level {@code @Tag} gets
     * wrong: springdoc adds it to every operation rather than treating it as an
     * overridable default, so both operations would be filed under both resources (M21.1).
     */
    static final String REQUEST_LOGS_TAG = "Request logs";
    static final String USAGE_TAG = "Usage";

    private final RequestLogQueryService requestLogQueryService;
    private final CursorCodec cursorCodec;

    public RequestLogController(RequestLogQueryService requestLogQueryService, CursorCodec cursorCodec) {
        this.requestLogQueryService = requestLogQueryService;
        this.cursorCodec = cursorCodec;
    }

    @GetMapping("/v1/request_logs")
    @Operation(tags = REQUEST_LOGS_TAG, operationId = "listRequestLogs",
            summary = "List API request logs",
            description = """
                    Returns the API calls you have made, most recent first, cursor-paginated \
                    — the record you reach for when an integration behaved differently from \
                    how you expected.

                    Each entry carries the `requestId` the response returned, so an error a \
                    customer reported can be found here by the identifier they quote. Bodies \
                    and headers are **redacted at the edge before they are stored**: card \
                    numbers, API keys and secrets never reach this log, so what you see here \
                    is what was safe to keep rather than what was sent.

                    Entries are pruned on a schedule; older traffic survives as aggregates \
                    at `/v1/usage`.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of request log entries, "
                    + "most recent first."),
            @ApiResponse(responseCode = "400", description = PublicApiParameters.INVALID_LIST_QUERY,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(ref = ApiError.SCHEMA_REF)))})
    public CursorPage<RequestLogResponse> requestLogs(
            @Parameter(description = PublicApiParameters.LIMIT)
            @RequestParam(name = "limit", required = false) Integer limit,
            @Parameter(description = PublicApiParameters.STARTING_AFTER)
            @RequestParam(name = "starting_after", required = false) String startingAfter,
            @Parameter(description = PublicApiParameters.CREATED_AFTER)
            @RequestParam(name = "created_after", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdAfter,
            @Parameter(description = PublicApiParameters.CREATED_BEFORE)
            @RequestParam(name = "created_before", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdBefore,
            @Parameter(description = "Return only requests that returned this exact HTTP "
                    + "status.", example = "409")
            @RequestParam(name = "status_code", required = false) Integer statusCode,
            @Parameter(description = "Return only requests made with this HTTP method.",
                    example = "POST")
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
    @Operation(tags = USAGE_TAG, operationId = "getUsage",
            summary = "Summarize API usage",
            description = """
                    Returns how much of the API you used over a date range, broken down by \
                    day, key and route, with latency percentiles per bucket.

                    Dated rather than timestamped, because the aggregate is per UTC day — \
                    accepting an instant would imply a precision the stored data does not \
                    have. Usage is grouped by key, so two keys hitting the same route on the \
                    same day appear as two buckets rather than one merged number; \
                    percentiles cannot be meaningfully averaged across keys, which is why \
                    they are not.

                    This outlives the raw request log: entries there are pruned, and their \
                    counts are rolled forward into these aggregates first.""")
    @ApiResponse(responseCode = "200", description = "Usage totals for the range, and the "
            + "per-day, per-key, per-route breakdown.")
    public UsageSummaryResponse usage(
            @Parameter(description = "The first day to include, in UTC. Defaults to 30 days "
                    + "ago.", example = "2026-07-01")
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "The last day to include, in UTC. Defaults to today.",
                    example = "2026-07-29")
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
