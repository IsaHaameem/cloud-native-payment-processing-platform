package com.paymentflow.notification.web;

import com.paymentflow.common.dto.error.ApiError;
import com.paymentflow.common.dto.page.PageResponse;
import com.paymentflow.common.exception.UnauthorizedException;
import com.paymentflow.common.security.MerchantContext;
import com.paymentflow.common.security.MerchantContextHolder;
import com.paymentflow.notification.domain.WebhookDelivery;
import com.paymentflow.notification.domain.WebhookDeliveryAttempt;
import com.paymentflow.notification.domain.WebhookEvent;
import com.paymentflow.notification.dto.WebhookDeliveryResponse;
import com.paymentflow.notification.mapper.WebhookDeliveryMapper;
import com.paymentflow.notification.repository.WebhookEventRepository;
import com.paymentflow.notification.service.WebhookDeliveryQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The delivery log and manual replay (M18.8, §4.5) — the half of this milestone that
 * makes the other half debuggable. Same authentication, scoping, and {@code
 * webhooks:manage} scope as the endpoint management API.
 *
 * <p>Paginated with V1's offset-based {@link PageResponse}, deliberately not cursors:
 * D107 introduces signed cursor pagination in **M19**, as one consistent decision applied
 * across every public list endpoint at once. Adopting a second convention here, one
 * milestone early and for one resource, is exactly the drift M19 exists to prevent — and
 * §5/M19 explicitly retains `PageResponse` for endpoints that predate it.
 */
@RestController
@RequestMapping("/v1/webhook_deliveries")
public class WebhookDeliveryController {

    /**
     * Declared with its description in {@code OpenApiConfig}. Set per operation rather
     * than as a class-level {@code @Tag}, which springdoc adds to every operation instead
     * of treating as an overridable default (M21.1).
     */
    static final String WEBHOOK_DELIVERIES_TAG = "Webhook deliveries";

    /** Reused by two operations; an annotation attribute must be a constant. */
    private static final String DELIVERY_NOT_FOUND_DESCRIPTION = """
            No such delivery. Also returned when it exists but belongs to another merchant, \
            or to the other mode — never `403`, which would confirm it exists.""";

    private final WebhookDeliveryQueryService queryService;
    private final WebhookEventRepository eventRepository;
    private final WebhookDeliveryMapper mapper;

    public WebhookDeliveryController(WebhookDeliveryQueryService queryService,
                                     WebhookEventRepository eventRepository, WebhookDeliveryMapper mapper) {
        this.queryService = queryService;
        this.eventRepository = eventRepository;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(tags = WEBHOOK_DELIVERIES_TAG, operationId = "listWebhookDeliveries",
            summary = "List webhook deliveries",
            description = """
                    Returns every delivery made to your endpoints, most recent first, each \
                    with its full attempt history — the request sent and the response \
                    received, per attempt.

                    This is what to read when a webhook did not arrive. The useful answer is \
                    almost never "it failed"; it is "it failed with 502 and this body, four \
                    times", which is here and nowhere else.

                    Offset-paginated with `page` and `size` rather than the cursor pagination \
                    the rest of the public API uses — this endpoint predates that contract \
                    and was deliberately not retrofitted (D139).""")
    @ApiResponse(responseCode = "200", description = "A page of deliveries, most recent first, "
            + "each with its attempts.")
    // M21.2: without @ParameterObject springdoc publishes `Pageable` as a single required
    // `pageable` object parameter — the Java binding type rather than the `page`/`size`/
    // `sort` query parameters a caller actually sends. That is the same defect class M21.1
    // found on the metadata filter's unnamed Map: a generator turns it into an SDK
    // argument nobody can supply. This annotation expands it into the real parameters and
    // changes nothing about the binding.
    public PageResponse<WebhookDeliveryResponse> list(@ParameterObject Pageable pageable) {
        MerchantContext context = requireContext();
        Page<WebhookDelivery> page = queryService.list(context.merchantId(), context.mode(), pageable);

        // One query for the page's attempts and one for its events, rather than two per row.
        Map<UUID, List<WebhookDeliveryAttempt>> attempts =
                queryService.attemptsOf(page.getContent().stream().map(WebhookDelivery::getId).toList());
        Map<UUID, WebhookEvent> events = eventRepository.findAllById(
                        page.getContent().stream()
                                .map(WebhookDelivery::getWebhookEventId)
                                .filter(java.util.Objects::nonNull)
                                .toList()).stream()
                .collect(Collectors.toMap(WebhookEvent::getId, Function.identity()));

        List<WebhookDeliveryResponse> content = page.getContent().stream()
                .map(delivery -> mapper.toResponse(delivery, events.get(delivery.getWebhookEventId()),
                        attempts.getOrDefault(delivery.getId(), List.of())))
                .toList();
        return PageResponse.of(content, page.getNumber(), page.getSize(), page.getTotalElements());
    }

    @GetMapping("/{id}")
    @Operation(tags = WEBHOOK_DELIVERIES_TAG, operationId = "getWebhookDelivery",
            summary = "Retrieve a webhook delivery",
            description = """
                    Returns one delivery with every attempt made for it, including the \
                    `PaymentFlow-Signature` header sent — which is what to compare against \
                    when a signature you computed does not match ours.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The delivery and its attempts."),
            @ApiResponse(responseCode = "404", description = DELIVERY_NOT_FOUND_DESCRIPTION,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(ref = ApiError.SCHEMA_REF)))})
    public WebhookDeliveryResponse get(@Parameter(description = "The delivery to retrieve.")
                                       @PathVariable UUID id) {
        MerchantContext context = requireContext();
        WebhookDelivery delivery = queryService.get(context.merchantId(), context.mode(), id);
        return mapper.toResponse(delivery, eventOf(delivery), queryService.attemptsOf(delivery.getId()));
    }

    /** Re-sends a past delivery as a new one. The original is never modified. */
    @PostMapping("/{id}/replay")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(tags = WEBHOOK_DELIVERIES_TAG, operationId = "replayWebhookDelivery",
            summary = "Replay a webhook delivery",
            description = """
                    Sends the same event to the same endpoint again, as a **new** delivery. \
                    The original is never modified — it keeps its own history, and the replay \
                    records `replayedFromDeliveryId` so the relationship stays visible.

                    Use this after fixing a receiver that was rejecting deliveries, or after \
                    re-enabling an endpoint the platform disabled. The response describes a \
                    delivery that has been *dispatched*, not delivered: it comes back with no \
                    attempts yet, because reporting more would be a promise the platform has \
                    not kept.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "A new delivery has been "
                    + "dispatched. It has no attempts yet."),
            @ApiResponse(responseCode = "404", description = DELIVERY_NOT_FOUND_DESCRIPTION,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(ref = ApiError.SCHEMA_REF))),
            @ApiResponse(responseCode = "409", description = """
                    The delivery cannot be replayed — its endpoint has since been deleted, so \
                    there is nowhere to send it.""",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(ref = ApiError.SCHEMA_REF)))})
    public WebhookDeliveryResponse replay(@Parameter(description = "The delivery to re-send.")
                                          @PathVariable UUID id) {
        MerchantContext context = requireContext();
        WebhookDelivery replay = queryService.replay(context.merchantId(), context.mode(), id);
        // Returned with no attempts yet: the replay has been dispatched, not delivered.
        // Reporting it as anything more would be a promise the platform has not kept yet.
        return mapper.toResponse(replay, eventOf(replay), List.of());
    }

    private WebhookEvent eventOf(WebhookDelivery delivery) {
        return delivery.getWebhookEventId() == null
                ? null
                : eventRepository.findById(delivery.getWebhookEventId()).orElse(null);
    }

    private static MerchantContext requireContext() {
        return MerchantContextHolder.get()
                .orElseThrow(() -> new UnauthorizedException("A verified internal context is required."));
    }
}
