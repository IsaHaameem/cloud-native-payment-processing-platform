package com.paymentflow.audit.web;

import com.paymentflow.audit.dto.EventResponse;
import com.paymentflow.audit.service.EventQueryService;
import com.paymentflow.common.dto.page.CursorPage;
import com.paymentflow.common.exception.UnauthorizedException;
import com.paymentflow.common.query.CursorCodec;
import com.paymentflow.common.query.ListQuery;
import com.paymentflow.common.security.MerchantContext;
import com.paymentflow.common.security.MerchantContextHolder;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * The merchant-facing event log (M19.5, §5/M19 task 4) — audit-service's first HTTP
 * surface since M7, closing half of V1 known issue #4.
 *
 * <p>Read-only, like transaction-service's: audit's trail is written solely by its Kafka
 * listeners (D44), and an immutable trail with a write endpoint would not be one.
 */
@RestController
public class EventController {

    /**
     * Declared with its description in {@code OpenApiConfig}. Set per operation rather
     * than as a class-level {@code @Tag}, which springdoc <em>adds</em> to every operation
     * instead of treating as an overridable default (M21.1) — harmless with one tag, but
     * the pattern is uniform across the platform's controllers so that adding a second
     * resource here later does not quietly file both under both.
     */
    static final String EVENTS_TAG = "Events";

    private final EventQueryService eventQueryService;
    private final CursorCodec cursorCodec;

    public EventController(EventQueryService eventQueryService, CursorCodec cursorCodec) {
        this.eventQueryService = eventQueryService;
        this.cursorCodec = cursorCodec;
    }

    @GetMapping("/v1/events")
    @Operation(tags = EVENTS_TAG)
    public CursorPage<EventResponse> list(
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "starting_after", required = false) String startingAfter,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "created_after", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdAfter,
            @RequestParam(name = "created_before", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdBefore) {

        MerchantContext context = requireContext();
        ListQuery query = ListQuery.resolve(limit, startingAfter, createdAfter, createdBefore,
                cursorCodec, context.merchantId(), context.mode());
        return eventQueryService.list(context.merchantId(), context.mode(), query, type);
    }

    /** Accepts the {@code evt_…} id a merchant received in a webhook body. */
    @GetMapping("/v1/events/{id}")
    @Operation(tags = EVENTS_TAG)
    public EventResponse get(@PathVariable String id) {
        MerchantContext context = requireContext();
        return eventQueryService.get(context.merchantId(), context.mode(), id);
    }

    private static MerchantContext requireContext() {
        return MerchantContextHolder.get()
                .orElseThrow(() -> new UnauthorizedException("A verified internal context is required."));
    }
}
