package com.paymentflow.payment.web;

import com.paymentflow.common.dto.error.ApiError;
import com.paymentflow.common.dto.page.CursorPage;
import com.paymentflow.common.openapi.PublicApiParameters;
import com.paymentflow.common.query.CursorCodec;
import com.paymentflow.common.query.ListQuery;
import com.paymentflow.common.query.MetadataFilterParams;
import com.paymentflow.payment.dto.CreatePaymentRequest;
import com.paymentflow.payment.dto.PaymentListFilter;
import com.paymentflow.payment.dto.PaymentResponse;
import com.paymentflow.payment.dto.RefundListFilter;
import com.paymentflow.payment.dto.RefundRequest;
import com.paymentflow.payment.dto.RefundResponse;
import com.paymentflow.payment.mapper.PaymentMapper;
import com.paymentflow.payment.merchant.MerchantResolver;
import com.paymentflow.payment.mode.RequestModeResolver;
import com.paymentflow.payment.service.PaymentQueryService;
import com.paymentflow.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.Explode;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.enums.ParameterStyle;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The public read surface for payments and refunds (M19.2/M19.3) — the {@code /v1} tier,
 * which is a versioned public promise, as opposed to {@link PaymentController}'s
 * {@code /api/v1} dashboard tier (D98).
 *
 * <p><b>Why a second controller rather than more methods on the first.</b> Before M19 the
 * gateway rewrote {@code /v1/payments} onto {@code /api/v1/payments}, so both tiers were
 * literally the same handler and necessarily returned the same list envelope. M19 gives
 * the public tier cursor pagination (D107) while the internal tier keeps offset
 * {@code PageResponse} — one handler cannot do both, and rewriting the internal tier to
 * cursors would change a contract the dashboard work in M23 has not been written against
 * yet, for no benefit. The gateway's rewrite is removed in M19.7; from then on each tier
 * is served by its own controller.
 *
 * <p>Query parameters are snake_case ({@code starting_after}, {@code amount_min}) while
 * response bodies stay camelCase. That looks inconsistent and is deliberate: it matches
 * what every comparable API does, because query strings are read and typed by humans far
 * more often than response fields are. M21 freezes both.
 *
 * <p>Merchant and mode come from the verified context via the same resolvers the mutation
 * path uses — never from a parameter (D28/§7 barrier ①), so there is no request shape in
 * which a caller can name someone else's data.
 *
 * <p>M21.1 adds the OpenAPI tags. They are named rather than left to springdoc, which
 * derives a tag from the class name and would publish {@code payment-v-1-controller} — a
 * Java identifier in a document integrators read, and one that would then organise the
 * documentation site's navigation (M25) and the SDKs' method grouping (M22). Two tags,
 * not one, because this class serves two resources: a reader looking for refunds should
 * not have to know they happen to be implemented next to payments. Their descriptions
 * live on the document in {@code OpenApiConfig}, which is where a merged multi-service
 * spec will need them.
 *
 * <p>Every operation carries its own tag and there is no class-level one, which is more
 * verbose than it looks like it needs to be. A class-level {@code @Tag} is <em>added</em>
 * to every operation in the class rather than acting as an overridable default — neither
 * a method-level {@code @Tag} nor {@code @Operation(tags = …)} replaces it — so the
 * refund operations came out tagged {@code Refunds} <em>and</em> {@code Payments}, which
 * would file them under both resources on the docs site. Caught by
 * {@code OpenApiDocumentIntegrationTest}; invisible to anything short of reading the
 * generated document.
 *
 * <p>The annotations here are about the *shape* of the published document — which
 * parameters exist, and where operations belong. The prose that describes each operation
 * and each field is the later annotation pass's work, not this one's.
 */
@RestController
public class PaymentV1Controller {

    /** Tag names are part of the published document; see the class javadoc. */
    static final String PAYMENTS_TAG = "Payments";
    static final String REFUNDS_TAG = "Refunds";

    /**
     * The name the {@code metadata} filter is published under.
     *
     * <p>The handler binds it as an unnamed {@code Map} of every request parameter,
     * because that is the only shape Spring will bind {@code metadata[key]=value} into
     * (see the argument's own comment). Left undescribed, springdoc publishes the Java
     * argument name — a required {@code requestParams} object parameter that does not
     * exist on the wire, and that a generator would turn into a mandatory SDK argument.
     * Naming it here documents the parameter merchants actually send.
     */
    private static final String METADATA_FILTER = "metadata";

    /**
     * Held as a constant because both list operations publish it and an annotation
     * attribute must be one; the alternative is the same paragraph typed twice, drifting.
     */
    private static final String METADATA_FILTER_DESCRIPTION = """
            Filter by metadata, spelled `metadata[key]=value` and repeatable. Matching is \
            containment and every named key must match: `?metadata[orderId]=A-1234&metadata[channel]=web` \
            returns only objects carrying both. A key nothing carries selects nothing rather \
            than everything.""";

    /** {@code expand} is a whitelist, not free text — see {@link #expandRefunds}. */
    private static final String EXPAND_REFUNDS = "refunds";

    /**
     * Prose reused across operations (M21.7). Held as constants for the same reason
     * {@link #METADATA_FILTER_DESCRIPTION} is: an annotation attribute must be a
     * compile-time constant, and the alternative is the same paragraph typed five times and
     * drifting.
     */
    private static final String EXPAND_DESCRIPTION = """
            Ask for related objects to be included inline. The only expandable relation on \
            this resource is `refunds`. A relation this revision does not have is ignored \
            rather than rejected, so an SDK written against a later version still works here.""";

    private static final String NOT_FOUND_DESCRIPTION = """
            No such payment. Also returned when the payment exists but belongs to another \
            merchant, or to the other mode — never `403`, because a `403` would confirm that \
            it exists.""";

    private static final String TRANSITION_CONFLICT_DESCRIPTION = """
            The payment is not in a state where this operation is legal — capturing one that \
            was never authorized, voiding one already captured, refunding more than remains. \
            The `code` says which.""";

    private static final String IDEMPOTENCY_CONFLICT_DESCRIPTION = """
            Another request with the same `Idempotency-Key` is still in flight. Distinct from \
            `CONFLICT` precisely because it is retryable — wait and send it again.""";

    private static final String VALIDATION_DESCRIPTION = """
            The request body failed validation. `errors` names every field that failed; the \
            rejected values are deliberately not echoed back.""";

    private final PaymentQueryService queryService;
    private final PaymentService paymentService;
    private final MerchantResolver merchantResolver;
    private final RequestModeResolver requestModeResolver;
    private final CursorCodec cursorCodec;
    private final PaymentMapper mapper;

    public PaymentV1Controller(PaymentQueryService queryService, PaymentService paymentService,
                               MerchantResolver merchantResolver,
                               RequestModeResolver requestModeResolver, CursorCodec cursorCodec,
                               PaymentMapper mapper) {
        this.queryService = queryService;
        this.paymentService = paymentService;
        this.merchantResolver = merchantResolver;
        this.requestModeResolver = requestModeResolver;
        this.cursorCodec = cursorCodec;
        this.mapper = mapper;
    }

    /**
     * The mutating half of the public payments surface.
     *
     * <p>Present here, rather than left to the gateway's rewrite onto {@code /api/v1},
     * because M19.7 removes that rewrite: once the two tiers return different list
     * envelopes they cannot share a handler, and a rewrite that covered only the
     * non-GET methods would mean {@code /v1/payments} was served by two different
     * controllers depending on the verb — which is exactly the kind of thing that is
     * correct on the day it is written and confusing forever after.
     *
     * <p>These delegate to the same {@link PaymentService} the {@code /api/v1} controller
     * calls, so there is one FSM, one idempotency guard, and one outbox — not a second
     * implementation of the payment lifecycle.
     */
    @PostMapping("/v1/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(tags = PAYMENTS_TAG, operationId = "createPayment",
            summary = "Create a payment",
            description = """
                    Creates a payment in the `created` state. Nothing is charged yet — \
                    creating a payment reserves an intent to charge, and authorization is a \
                    separate call, so a client can build the object before it has a \
                    payment method to authorize against.

                    The `mode` of the resulting payment comes from your API key: an \
                    `sk_test_` key creates test payments, an `sk_live_` key live ones. \
                    Nothing in this request can change that.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The payment was created."),
            @ApiResponse(responseCode = "400", description = VALIDATION_DESCRIPTION,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(ref = ApiError.SCHEMA_REF))),
            @ApiResponse(responseCode = "409", description = IDEMPOTENCY_CONFLICT_DESCRIPTION,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(ref = ApiError.SCHEMA_REF)))})
    public PaymentResponse create(@Valid @RequestBody CreatePaymentRequest request,
                                  @Parameter(description = PublicApiParameters.IDEMPOTENCY_KEY, required = true)
                                  @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return paymentService.create(request, idempotencyKey);
    }

    @PostMapping("/v1/payments/{id}/authorize")
    @Operation(tags = PAYMENTS_TAG, operationId = "authorizePayment",
            summary = "Authorize a payment",
            description = """
                    Places a hold on the customer's funds for the payment's full amount, \
                    moving it to `authorized`. No money moves until you capture.

                    In test mode the outcome is decided by the `paymentMethodToken` the \
                    payment carries — see `GET /v1/test/cards` for the tokens that approve, \
                    decline, or fail — so every branch of your integration is reachable on \
                    demand rather than by chance.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The payment was authorized, or "
                    + "the acquirer declined it and the payment is now `failed`."),
            @ApiResponse(responseCode = "404", description = NOT_FOUND_DESCRIPTION,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(ref = ApiError.SCHEMA_REF))),
            @ApiResponse(responseCode = "409", description = TRANSITION_CONFLICT_DESCRIPTION,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(ref = ApiError.SCHEMA_REF)))})
    public PaymentResponse authorize(@Parameter(description = "The payment to authorize.")
                                     @PathVariable UUID id,
                                     @Parameter(description = PublicApiParameters.IDEMPOTENCY_KEY, required = true)
                                     @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return paymentService.authorize(id, idempotencyKey);
    }

    @PostMapping("/v1/payments/{id}/capture")
    @Operation(tags = PAYMENTS_TAG, operationId = "capturePayment",
            summary = "Capture an authorized payment",
            description = """
                    Moves the authorized funds, settling the payment. Capture is all-or-nothing \
                    here: the full authorized amount is taken, and a capture can never exceed \
                    the authorization it settles.

                    Capturing an already-captured payment is a `409` rather than a silent \
                    success — if you need retry safety, send an `Idempotency-Key` and replay \
                    the same one.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The payment was captured."),
            @ApiResponse(responseCode = "404", description = NOT_FOUND_DESCRIPTION,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(ref = ApiError.SCHEMA_REF))),
            @ApiResponse(responseCode = "409", description = TRANSITION_CONFLICT_DESCRIPTION,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(ref = ApiError.SCHEMA_REF)))})
    public PaymentResponse capture(@Parameter(description = "The payment to capture.")
                                   @PathVariable UUID id,
                                   @Parameter(description = PublicApiParameters.IDEMPOTENCY_KEY, required = true)
                                   @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return paymentService.capture(id, idempotencyKey);
    }

    @PostMapping("/v1/payments/{id}/refund")
    @Operation(tags = PAYMENTS_TAG, operationId = "refundPayment",
            summary = "Refund a captured payment",
            description = """
                    Returns captured funds to the customer, in full or in part. Send no body \
                    to refund everything that remains.

                    Refunds are first-class objects with their own ids — this call returns \
                    the updated **payment**, and the refund it created is readable at \
                    `GET /v1/refunds` or inline via `GET /v1/payments/{id}?expand=refunds`. \
                    Partial refunds accumulate: `refundedAmountMinor` can never exceed \
                    `capturedAmountMinor`.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The refund was issued; the "
                    + "payment is returned with its updated `refundedAmountMinor`."),
            @ApiResponse(responseCode = "400", description = VALIDATION_DESCRIPTION,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(ref = ApiError.SCHEMA_REF))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND_DESCRIPTION,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(ref = ApiError.SCHEMA_REF))),
            @ApiResponse(responseCode = "409", description = TRANSITION_CONFLICT_DESCRIPTION,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(ref = ApiError.SCHEMA_REF)))})
    public PaymentResponse refund(@Parameter(description = "The payment to refund.")
                                  @PathVariable UUID id,
                                  @Valid @RequestBody(required = false) RefundRequest request,
                                  @Parameter(description = PublicApiParameters.IDEMPOTENCY_KEY, required = true)
                                  @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return paymentService.refund(id, request, idempotencyKey);
    }

    @PostMapping("/v1/payments/{id}/void")
    @Operation(tags = PAYMENTS_TAG, operationId = "voidPayment",
            summary = "Void a payment",
            description = """
                    Releases an authorization without taking the money, moving the payment to \
                    `voided`. This is the counterpart to capture, not to refund: void applies \
                    before funds have moved, refund after. A captured payment cannot be \
                    voided — refund it instead.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The authorization was released."),
            @ApiResponse(responseCode = "404", description = NOT_FOUND_DESCRIPTION,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(ref = ApiError.SCHEMA_REF))),
            @ApiResponse(responseCode = "409", description = TRANSITION_CONFLICT_DESCRIPTION,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(ref = ApiError.SCHEMA_REF)))})
    public PaymentResponse voidPayment(@Parameter(description = "The payment to void.")
                                       @PathVariable UUID id,
                                       @Parameter(description = PublicApiParameters.IDEMPOTENCY_KEY, required = true)
                                       @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return paymentService.voidPayment(id, idempotencyKey);
    }

    @GetMapping("/v1/payments")
    @Operation(tags = PAYMENTS_TAG, operationId = "listPayments",
            summary = "List payments",
            description = """
                    Returns your payments, most recent first, cursor-paginated.

                    Pagination is cursor-based rather than offset-based because this list is \
                    append-heavy: under concurrent inserts an offset page silently skips \
                    rows, which on a financial list is a correctness bug rather than a \
                    cosmetic one. Follow `nextCursor` until `hasMore` is false.

                    Every filter combines with `AND`. The list is always scoped to your own \
                    merchant and to the mode of the key you called with.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of payments, most recent first."),
            @ApiResponse(responseCode = "400", description = PublicApiParameters.INVALID_LIST_QUERY,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(ref = ApiError.SCHEMA_REF)))})
    @Parameters(@Parameter(name = METADATA_FILTER, in = ParameterIn.QUERY,
            style = ParameterStyle.DEEPOBJECT, explode = Explode.TRUE,
            description = METADATA_FILTER_DESCRIPTION,
            schema = @Schema(type = "object", additionalProperties = Schema.AdditionalPropertiesValue.TRUE)))
    public CursorPage<PaymentResponse> listPayments(
            @Parameter(description = PublicApiParameters.LIMIT)
            @RequestParam(name = "limit", required = false) Integer limit,
            @Parameter(description = PublicApiParameters.STARTING_AFTER)
            @RequestParam(name = "starting_after", required = false) String startingAfter,
            @Parameter(description = """
                    Return only payments in this state. Must be one of the statuses this \
                    revision defines — a typo is a `400` naming the vocabulary rather than an \
                    empty page you then have to explain to yourself.""",
                    example = "authorized")
            @RequestParam(name = "status", required = false) String status,
            @Parameter(description = "Return only payments in this ISO 4217 currency.",
                    example = "USD")
            @RequestParam(name = "currency", required = false) String currency,
            @Parameter(description = "Return only payments of at least this amount, in minor "
                    + "units.", example = "1000")
            @RequestParam(name = "amount_min", required = false) Long amountMin,
            @Parameter(description = "Return only payments of at most this amount, in minor "
                    + "units.", example = "100000")
            @RequestParam(name = "amount_max", required = false) Long amountMax,
            @Parameter(description = PublicApiParameters.CREATED_AFTER)
            @RequestParam(name = "created_after", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdAfter,
            @Parameter(description = PublicApiParameters.CREATED_BEFORE)
            @RequestParam(name = "created_before", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdBefore,
            @Parameter(description = EXPAND_DESCRIPTION, example = EXPAND_REFUNDS)
            @RequestParam(name = "expand", required = false) String expand,
            // Unnamed and deliberately: Spring binds a Map to "every request parameter"
            // only when the annotation carries no name, and `metadata[key]=value` cannot
            // be bound any other way. See MetadataFilterParams for the bug this shape
            // fixes — the named spelling bound nothing and the filter failed open.
            //
            // Hidden (M21.1) and re-declared under its real name by @Parameters above:
            // this argument is a binding mechanism, not a wire parameter, and published
            // as-is it appeared as a required `requestParams` object nobody can send.
            @Parameter(hidden = true) @RequestParam Map<String, String> requestParams) {

        UUID merchantId = merchantId();
        String mode = requestModeResolver.resolve();
        ListQuery query = ListQuery.resolve(limit, startingAfter, createdAfter, createdBefore,
                cursorCodec, merchantId, mode);
        PaymentListFilter filter = PaymentListFilter.of(status, currency, amountMin, amountMax,
                MetadataFilterParams.from(requestParams), mapper::writeMetadata);

        return queryService.listPayments(merchantId, mode, query, filter, expandRefunds(expand));
    }

    @GetMapping("/v1/payments/{id}")
    @Operation(tags = PAYMENTS_TAG, operationId = "getPayment",
            summary = "Retrieve a payment",
            description = """
                    Returns one payment by id, including how much of it has been captured \
                    and refunded so far. Add `?expand=refunds` to receive the individual \
                    refunds inline instead of fetching them separately.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The payment."),
            @ApiResponse(responseCode = "404", description = NOT_FOUND_DESCRIPTION,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(ref = ApiError.SCHEMA_REF)))})
    public PaymentResponse getPayment(@Parameter(description = "The payment to retrieve.")
                                      @PathVariable UUID id,
                                      @Parameter(description = EXPAND_DESCRIPTION, example = EXPAND_REFUNDS)
                                      @RequestParam(name = "expand", required = false) String expand) {
        return queryService.getPayment(merchantId(), requestModeResolver.resolve(), id, expandRefunds(expand));
    }

    @GetMapping("/v1/refunds")
    @Operation(tags = REFUNDS_TAG, operationId = "listRefunds",
            summary = "List refunds",
            description = """
                    Returns the refunds you have issued, most recent first, cursor-paginated \
                    on the same contract as the payments list.

                    Refunds became first-class objects in this platform's M19 release, so \
                    this list starts there — a payment refunded before then carries the \
                    refunded total on the payment itself but has no refund object here.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of refunds, most recent first."),
            @ApiResponse(responseCode = "400", description = PublicApiParameters.INVALID_LIST_QUERY,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(ref = ApiError.SCHEMA_REF)))})
    @Parameters(@Parameter(name = METADATA_FILTER, in = ParameterIn.QUERY,
            style = ParameterStyle.DEEPOBJECT, explode = Explode.TRUE,
            description = METADATA_FILTER_DESCRIPTION,
            schema = @Schema(type = "object", additionalProperties = Schema.AdditionalPropertiesValue.TRUE)))
    public CursorPage<RefundResponse> listRefunds(
            @Parameter(description = PublicApiParameters.LIMIT)
            @RequestParam(name = "limit", required = false) Integer limit,
            @Parameter(description = PublicApiParameters.STARTING_AFTER)
            @RequestParam(name = "starting_after", required = false) String startingAfter,
            @Parameter(description = "Return only refunds issued against this payment.")
            @RequestParam(name = "payment", required = false) UUID paymentId,
            @Parameter(description = "Return only refunds in this state.", example = "succeeded")
            @RequestParam(name = "status", required = false) String status,
            @Parameter(description = PublicApiParameters.CREATED_AFTER)
            @RequestParam(name = "created_after", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdAfter,
            @Parameter(description = PublicApiParameters.CREATED_BEFORE)
            @RequestParam(name = "created_before", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdBefore,
            // Same unnamed-Map binding, hidden for the same reason, as the payments list
            // above.
            @Parameter(hidden = true) @RequestParam Map<String, String> requestParams) {

        UUID merchantId = merchantId();
        String mode = requestModeResolver.resolve();
        ListQuery query = ListQuery.resolve(limit, startingAfter, createdAfter, createdBefore,
                cursorCodec, merchantId, mode);
        RefundListFilter filter = RefundListFilter.of(paymentId, status,
                MetadataFilterParams.from(requestParams), mapper::writeMetadata);

        return queryService.listRefunds(merchantId, mode, query, filter);
    }

    @GetMapping("/v1/refunds/{id}")
    @Operation(tags = REFUNDS_TAG, operationId = "getRefund",
            summary = "Retrieve a refund",
            description = "Returns one refund by id, including the payment it was issued "
                    + "against and why it was issued.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The refund."),
            @ApiResponse(responseCode = "404", description = """
                    No such refund. Also returned when the refund exists but belongs to \
                    another merchant, or to the other mode.""",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(ref = ApiError.SCHEMA_REF)))})
    public RefundResponse getRefund(@Parameter(description = "The refund to retrieve.")
                                    @PathVariable UUID id) {
        return queryService.getRefund(merchantId(), requestModeResolver.resolve(), id);
    }

    /**
     * A closed whitelist with exactly one accepted value, and no nesting.
     *
     * <p>M19's testing strategy calls for "{@code expand} depth limits". The limit here is
     * structural rather than enforced: there is one expandable relation and it cannot
     * itself be expanded, so an unbounded expansion tree — the thing depth limits exist to
     * prevent — has no way to exist. An unrecognised value is ignored rather than
     * rejected, because {@code expand} naming a relation this version does not have is
     * exactly the forward-compatible case §4.10 requires clients to tolerate.
     */
    private static boolean expandRefunds(String expand) {
        return expand != null && EXPAND_REFUNDS.equalsIgnoreCase(expand.trim());
    }

    private UUID merchantId() {
        return merchantResolver.resolveCallerMerchant().id();
    }
}
