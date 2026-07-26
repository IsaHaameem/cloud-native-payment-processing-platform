package com.paymentflow.transaction.web;

import com.paymentflow.common.dto.page.CursorPage;
import com.paymentflow.common.exception.UnauthorizedException;
import com.paymentflow.common.query.CursorCodec;
import com.paymentflow.common.query.ListQuery;
import com.paymentflow.common.security.MerchantContext;
import com.paymentflow.common.security.MerchantContextHolder;
import com.paymentflow.transaction.dto.BalanceResponse;
import com.paymentflow.transaction.dto.BalanceTransactionResponse;
import com.paymentflow.transaction.service.BalanceQueryService;
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

    private final BalanceQueryService balanceQueryService;
    private final CursorCodec cursorCodec;

    public BalanceController(BalanceQueryService balanceQueryService, CursorCodec cursorCodec) {
        this.balanceQueryService = balanceQueryService;
        this.cursorCodec = cursorCodec;
    }

    @GetMapping("/v1/balance")
    public BalanceResponse balance() {
        MerchantContext context = requireContext();
        return balanceQueryService.balance(context.merchantId(), context.mode());
    }

    @GetMapping("/v1/balance_transactions")
    public CursorPage<BalanceTransactionResponse> balanceTransactions(
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "starting_after", required = false) String startingAfter,
            @RequestParam(name = "created_after", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdAfter,
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
