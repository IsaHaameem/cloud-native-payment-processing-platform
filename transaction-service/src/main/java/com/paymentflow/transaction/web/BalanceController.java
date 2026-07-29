package com.paymentflow.transaction.web;

import com.paymentflow.common.dto.error.ApiError;
import com.paymentflow.common.dto.page.CursorPage;
import com.paymentflow.common.exception.UnauthorizedException;
import com.paymentflow.common.openapi.PublicApiParameters;
import com.paymentflow.common.query.CursorCodec;
import com.paymentflow.common.query.ListQuery;
import com.paymentflow.common.security.MerchantContext;
import com.paymentflow.common.security.MerchantContextHolder;
import com.paymentflow.transaction.dto.BalanceResponse;
import com.paymentflow.transaction.dto.BalanceTransactionResponse;
import com.paymentflow.transaction.service.BalanceQueryService;
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

/**
 * The ledger's public read surface (M19.4, §5/M19 task 3) — transaction-service's first
 * HTTP endpoints since it was built in M6, closing V1 known issue #3.
 *
 * <p><b>There is deliberately no write endpoint, and that is the design rather than an
 * omission.</b> The ledger is written only by the Kafka consumer (D42), and that
 * invariant is what makes the double-entry guarantees provable: every posting is balanced
 * because exactly one code path creates them. M19 adds a way to read the ledger without
 * adding a way to write it — {@link BalanceQueryService} holds no writer, so there is
 * nothing a future endpoint added here could accidentally reach.
 *
 * <p>Merchant and mode come from the verified {@link MerchantContext} (D100), never from a
 * parameter — the same §7 barrier every other public read enforces.
 */
@RestController
public class BalanceController {

    /**
     * Tag names are part of the published document, and are declared with their
     * descriptions in {@code OpenApiConfig}. Set per operation with no class-level
     * {@code @Tag}: a class-level tag is <em>added</em> to every operation rather than
     * acting as an overridable default, so the two operations here would each come out
     * filed under both resources (M21.1).
     */
    static final String BALANCE_TAG = "Balance";
    static final String BALANCE_TRANSACTIONS_TAG = "Balance transactions";

    private final BalanceQueryService balanceQueryService;
    private final CursorCodec cursorCodec;

    public BalanceController(BalanceQueryService balanceQueryService, CursorCodec cursorCodec) {
        this.balanceQueryService = balanceQueryService;
        this.cursorCodec = cursorCodec;
    }

    @GetMapping("/v1/balance")
    @Operation(tags = BALANCE_TAG, operationId = "getBalance",
            summary = "Retrieve your balance",
            description = """
                    Returns what you hold, per currency, in the mode of the key you called \
                    with.

                    Two figures per currency, and the difference matters: `pendingMinor` is \
                    money authorized but not yet captured — it can still be voided or expire, \
                    so it is not yours — while `availableMinor` is money captured and owed to \
                    you, net of refunds. Both are projections of the double-entry ledger \
                    rather than counters kept alongside it, so they cannot drift from the \
                    entries at `/v1/balance_transactions`.""")
    @ApiResponse(responseCode = "200", description = "Your balance in every currency you hold.")
    public BalanceResponse balance() {
        MerchantContext context = requireContext();
        return balanceQueryService.balance(context.merchantId(), context.mode());
    }

    @GetMapping("/v1/balance_transactions")
    @Operation(tags = BALANCE_TRANSACTIONS_TAG, operationId = "listBalanceTransactions",
            summary = "List balance transactions",
            description = """
                    Returns the individual ledger entries behind your balance, most recent \
                    first, cursor-paginated. Every entry names the payment and the lifecycle \
                    event that produced it, so a balance can always be reconciled back to the \
                    payments that made it.

                    Only your own side of each entry is returned. Every posting is balanced \
                    against the platform's clearing account, which is not a merchant's \
                    business to see — this is a projection of the ledger, not a dump of it.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of ledger entries, most "
                    + "recent first."),
            @ApiResponse(responseCode = "400", description = PublicApiParameters.INVALID_LIST_QUERY,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(ref = ApiError.SCHEMA_REF)))})
    public CursorPage<BalanceTransactionResponse> balanceTransactions(
            @Parameter(description = PublicApiParameters.LIMIT)
            @RequestParam(name = "limit", required = false) Integer limit,
            @Parameter(description = PublicApiParameters.STARTING_AFTER)
            @RequestParam(name = "starting_after", required = false) String startingAfter,
            @Parameter(description = PublicApiParameters.CREATED_AFTER)
            @RequestParam(name = "created_after", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdAfter,
            @Parameter(description = PublicApiParameters.CREATED_BEFORE)
            @RequestParam(name = "created_before", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdBefore) {

        MerchantContext context = requireContext();
        ListQuery query = ListQuery.resolve(limit, startingAfter, createdAfter, createdBefore,
                cursorCodec, context.merchantId(), context.mode());
        return balanceQueryService.balanceTransactions(context.merchantId(), context.mode(), query);
    }

    private static MerchantContext requireContext() {
        return MerchantContextHolder.get()
                .orElseThrow(() -> new UnauthorizedException("A verified internal context is required."));
    }
}
