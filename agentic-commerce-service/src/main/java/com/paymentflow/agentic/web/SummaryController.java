package com.paymentflow.agentic.web;

import com.paymentflow.agentic.error.AgenticErrorCode;
import com.paymentflow.agentic.error.AgenticException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * The agentic aggregate metrics endpoint (G-1).
 *
 * <pre>
 *   GET /api/agentic/summary?from=&to=      ISO-8601 instants; both optional
 * </pre>
 *
 * <p>Merchant- and mode-scoped from the verified context. {@code from} defaults to the epoch and
 * {@code to} to now, so the bare call is "everything, all time". A {@code from} after {@code to}
 * is a 400 rather than a silently empty window.
 */
@RestController
@RequestMapping("/api/agentic/summary")
public class SummaryController {

    private final SummaryService summaryService;
    private final AgenticCallerContext callerContext;

    public SummaryController(SummaryService summaryService, AgenticCallerContext callerContext) {
        this.summaryService = summaryService;
        this.callerContext = callerContext;
    }

    @GetMapping
    public SummaryService.SummaryView get(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        AgenticCallerContext.Caller caller = callerContext.resolve();
        if (from != null && to != null && from.isAfter(to)) {
            throw new AgenticException(AgenticErrorCode.TOOL_ARGUMENTS_INVALID,
                    "'from' must not be after 'to'.");
        }
        return summaryService.summarize(caller.merchantId(), caller.mode(), from, to);
    }
}
