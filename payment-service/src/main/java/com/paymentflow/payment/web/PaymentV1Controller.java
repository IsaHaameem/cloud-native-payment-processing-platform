package com.paymentflow.payment.web;

import com.paymentflow.common.dto.page.CursorPage;
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
import io.swagger.v3.oas.annotations.media.Schema;
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
    @Operation(tags = PAYMENTS_TAG)
    public PaymentResponse create(@Valid @RequestBody CreatePaymentRequest request,
                                  @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return paymentService.create(request, idempotencyKey);
    }

    @PostMapping("/v1/payments/{id}/authorize")
    @Operation(tags = PAYMENTS_TAG)
    public PaymentResponse authorize(@PathVariable UUID id,
                                     @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return paymentService.authorize(id, idempotencyKey);
    }

    @PostMapping("/v1/payments/{id}/capture")
    @Operation(tags = PAYMENTS_TAG)
    public PaymentResponse capture(@PathVariable UUID id,
                                   @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return paymentService.capture(id, idempotencyKey);
    }

    @PostMapping("/v1/payments/{id}/refund")
    @Operation(tags = PAYMENTS_TAG)
    public PaymentResponse refund(@PathVariable UUID id,
                                  @Valid @RequestBody(required = false) RefundRequest request,
                                  @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return paymentService.refund(id, request, idempotencyKey);
    }

    @PostMapping("/v1/payments/{id}/void")
    @Operation(tags = PAYMENTS_TAG)
    public PaymentResponse voidPayment(@PathVariable UUID id,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return paymentService.voidPayment(id, idempotencyKey);
    }

    @GetMapping("/v1/payments")
    @Operation(tags = PAYMENTS_TAG)
    @Parameters(@Parameter(name = METADATA_FILTER, in = ParameterIn.QUERY,
            style = ParameterStyle.DEEPOBJECT, explode = Explode.TRUE,
            description = METADATA_FILTER_DESCRIPTION,
            schema = @Schema(type = "object", additionalProperties = Schema.AdditionalPropertiesValue.TRUE)))
    public CursorPage<PaymentResponse> listPayments(
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "starting_after", required = false) String startingAfter,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "currency", required = false) String currency,
            @RequestParam(name = "amount_min", required = false) Long amountMin,
            @RequestParam(name = "amount_max", required = false) Long amountMax,
            @RequestParam(name = "created_after", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdAfter,
            @RequestParam(name = "created_before", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdBefore,
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
    @Operation(tags = PAYMENTS_TAG)
    public PaymentResponse getPayment(@PathVariable UUID id,
                                      @RequestParam(name = "expand", required = false) String expand) {
        return queryService.getPayment(merchantId(), requestModeResolver.resolve(), id, expandRefunds(expand));
    }

    @GetMapping("/v1/refunds")
    @Operation(tags = REFUNDS_TAG)
    @Parameters(@Parameter(name = METADATA_FILTER, in = ParameterIn.QUERY,
            style = ParameterStyle.DEEPOBJECT, explode = Explode.TRUE,
            description = METADATA_FILTER_DESCRIPTION,
            schema = @Schema(type = "object", additionalProperties = Schema.AdditionalPropertiesValue.TRUE)))
    public CursorPage<RefundResponse> listRefunds(
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "starting_after", required = false) String startingAfter,
            @RequestParam(name = "payment", required = false) UUID paymentId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "created_after", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdAfter,
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
    @Operation(tags = REFUNDS_TAG)
    public RefundResponse getRefund(@PathVariable UUID id) {
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
