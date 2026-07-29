package com.paymentflow.audit.web;

import com.paymentflow.audit.dto.EventResponse;
import com.paymentflow.audit.service.EventQueryService;
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
    @Operation(tags = EVENTS_TAG, operationId = "listEvents",
            summary = "List events",
            description = """
                    Returns everything that has happened on your account, most recent first, \
                    cursor-paginated.

                    This is the same feed your webhook endpoints are delivered from, which \
                    makes it the recovery path when they are not: an endpoint that was down, \
                    misconfigured, or auto-disabled loses deliveries, not events. Poll here \
                    to catch up, and reconcile by `id` — an event's `evt_` id is identical in \
                    both places.

                    Ordered by when things **occurred**, not when they were recorded, so a \
                    redelivery cannot reorder your feed.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of events, most recent first."),
            @ApiResponse(responseCode = "400", description = PublicApiParameters.INVALID_LIST_QUERY,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(ref = ApiError.SCHEMA_REF)))})
    public CursorPage<EventResponse> list(
            @Parameter(description = PublicApiParameters.LIMIT)
            @RequestParam(name = "limit", required = false) Integer limit,
            @Parameter(description = PublicApiParameters.STARTING_AFTER)
            @RequestParam(name = "starting_after", required = false) String startingAfter,
            @Parameter(description = """
                    Return only events of this type. Must be a name from the event \
                    vocabulary — a typo is a `400` naming the valid values rather than an \
                    empty page.""",
                    example = "payment.captured")
            @RequestParam(name = "type", required = false) String type,
            @Parameter(description = PublicApiParameters.CREATED_AFTER)
            @RequestParam(name = "created_after", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdAfter,
            @Parameter(description = PublicApiParameters.CREATED_BEFORE)
            @RequestParam(name = "created_before", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdBefore) {

        MerchantContext context = requireContext();
        ListQuery query = ListQuery.resolve(limit, startingAfter, createdAfter, createdBefore,
                cursorCodec, context.merchantId(), context.mode());
        return eventQueryService.list(context.merchantId(), context.mode(), query, type);
    }

    /** Accepts the {@code evt_…} id a merchant received in a webhook body. */
    @GetMapping("/v1/events/{id}")
    @Operation(tags = EVENTS_TAG, operationId = "getEvent",
            summary = "Retrieve an event",
            description = """
                    Returns one event by the `evt_` id you received in a webhook body. This \
                    is how a receiver confirms that a delivery it was sent is genuine and \
                    still says what it said — the id in the webhook and the id here are the \
                    same value, derived from the same source.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The event."),
            @ApiResponse(responseCode = "400", description = "The id is not a well-formed "
                    + "`evt_` identifier.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(ref = ApiError.SCHEMA_REF))),
            @ApiResponse(responseCode = "404", description = """
                    No such event. Also returned for an event that exists but belongs to \
                    another merchant, to the other mode, or is internal to the platform and \
                    not part of your feed — never `403`, which would confirm it exists.""",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(ref = ApiError.SCHEMA_REF)))})
    public EventResponse get(@Parameter(description = "The `evt_` id of the event to retrieve.",
                                     example = "evt_9f2c1e7a4b8d4c3e8a1d2b4f6a8c05d1")
                             @PathVariable String id) {
        MerchantContext context = requireContext();
        return eventQueryService.get(context.merchantId(), context.mode(), id);
    }

    private static MerchantContext requireContext() {
        return MerchantContextHolder.get()
                .orElseThrow(() -> new UnauthorizedException("A verified internal context is required."));
    }
}
