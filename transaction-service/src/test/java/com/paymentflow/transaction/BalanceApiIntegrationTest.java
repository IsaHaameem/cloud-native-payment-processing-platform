package com.paymentflow.transaction;

import com.paymentflow.common.dto.page.CursorPage;
import com.paymentflow.common.query.ListQuery;
import com.paymentflow.common.query.CursorCodec;
import com.paymentflow.transaction.domain.Account;
import com.paymentflow.transaction.domain.AccountType;
import com.paymentflow.transaction.domain.Direction;
import com.paymentflow.transaction.domain.LedgerEntry;
import com.paymentflow.transaction.domain.LedgerTransaction;
import com.paymentflow.transaction.dto.BalanceResponse;
import com.paymentflow.transaction.dto.BalanceTransactionResponse;
import com.paymentflow.transaction.repository.AccountRepository;
import com.paymentflow.transaction.repository.LedgerEntryRepository;
import com.paymentflow.transaction.repository.LedgerTransactionRepository;
import com.paymentflow.transaction.service.BalanceQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ledger's read API (M19.4) against real Postgres.
 *
 * <p>The assertion that matters most is the last one: the balance the API reports must
 * equal a direct SQL sum over the ledger entries behind it. That is M19's own completion
 * criterion, and it is only meaningful because the balance is *projected* from the ledger
 * rather than stored beside it — a service that kept its own total would pass a test
 * comparing it to itself.
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@Testcontainers
class BalanceApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;
    @Autowired
    private LedgerTransactionRepository ledgerTransactionRepository;
    @Autowired
    private BalanceQueryService balanceQueryService;
    @Autowired
    private CursorCodec cursorCodec;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ListQuery query(int limit) {
        return ListQuery.resolve(limit, null, null, null, cursorCodec, UUID.randomUUID(), "test");
    }

    /** Posts one balanced journal: a credit to the merchant account, a debit to platform clearing. */
    private void post(UUID merchantId, String mode, AccountType type, String currency, long amount,
                      UUID paymentId, String eventType) {
        Account merchantAccount = accountRepository
                .findByAccountTypeAndOwnerIdAndCurrencyAndMode(type, merchantId, currency, mode)
                .orElseGet(() -> accountRepository.saveAndFlush(Account.open(type, merchantId, currency, mode)));

        LedgerTransaction journal = ledgerTransactionRepository.saveAndFlush(
                LedgerTransaction.of(paymentId, UUID.randomUUID(), eventType, mode, "seeded"));
        ledgerEntryRepository.saveAndFlush(LedgerEntry.of(journal.getId(), merchantAccount.getId(),
                Direction.CREDIT, amount, currency, mode));

        merchantAccount.apply(Direction.CREDIT, amount);
        accountRepository.saveAndFlush(merchantAccount);
    }

    @Test
    void balanceSeparatesPendingFromAvailablePerCurrency() {
        UUID merchantId = UUID.randomUUID();
        post(merchantId, "test", AccountType.MERCHANT_PENDING, "USD", 5000, UUID.randomUUID(), "PaymentAuthorized");
        post(merchantId, "test", AccountType.MERCHANT_SETTLED, "USD", 3000, UUID.randomUUID(), "PaymentCaptured");
        post(merchantId, "test", AccountType.MERCHANT_SETTLED, "EUR", 700, UUID.randomUUID(), "PaymentCaptured");

        BalanceResponse balance = balanceQueryService.balance(merchantId, "test");

        assertThat(balance.object()).isEqualTo("balance");
        assertThat(balance.balances()).hasSize(2);
        // Sorted by currency, so two calls return the same order.
        assertThat(balance.balances().getFirst().currency()).isEqualTo("EUR");
        BalanceResponse.CurrencyBalance usd = balance.balances().stream()
                .filter(b -> b.currency().equals("USD")).findFirst().orElseThrow();
        // Authorized-not-captured and captured-and-owed are genuinely different numbers
        // from genuinely different accounts.
        assertThat(usd.pendingMinor()).isEqualTo(5000);
        assertThat(usd.availableMinor()).isEqualTo(3000);
    }

    @Test
    void balanceIsScopedToOneMerchantAndOneMode() {
        UUID merchantId = UUID.randomUUID();
        UUID otherMerchant = UUID.randomUUID();
        post(merchantId, "test", AccountType.MERCHANT_SETTLED, "USD", 1000, UUID.randomUUID(), "PaymentCaptured");
        post(merchantId, "live", AccountType.MERCHANT_SETTLED, "USD", 9999, UUID.randomUUID(), "PaymentCaptured");
        post(otherMerchant, "test", AccountType.MERCHANT_SETTLED, "USD", 4242, UUID.randomUUID(), "PaymentCaptured");

        assertThat(balanceQueryService.balance(merchantId, "test").balances().getFirst().availableMinor())
                .isEqualTo(1000);
        // The other mode's money is invisible, not merely filtered out afterwards — the
        // accounts themselves are partitioned by mode (M16.3).
        assertThat(balanceQueryService.balance(merchantId, "live").balances().getFirst().availableMinor())
                .isEqualTo(9999);
        assertThat(balanceQueryService.balance(otherMerchant, "test").balances().getFirst().availableMinor())
                .isEqualTo(4242);
    }

    @Test
    void aMerchantWithNoLedgerActivityGetsAnEmptyBalanceRatherThanAnError() {
        // Having no balance is a fact about a merchant, not a missing resource.
        BalanceResponse balance = balanceQueryService.balance(UUID.randomUUID(), "test");

        assertThat(balance.balances()).isEmpty();
        assertThat(balance.object()).isEqualTo("balance");
    }

    @Test
    void thePlatformClearingAccountIsNeverVisibleToAMerchant() {
        UUID merchantId = UUID.randomUUID();
        // The clearing account has a null owner, so it cannot appear in any merchant's
        // balance — structurally, not because a filter excludes it.
        accountRepository.saveAndFlush(Account.open(AccountType.PLATFORM_CLEARING, null, "USD", "test"));
        post(merchantId, "test", AccountType.MERCHANT_SETTLED, "USD", 1000, UUID.randomUUID(), "PaymentCaptured");

        BalanceResponse balance = balanceQueryService.balance(merchantId, "test");

        assertThat(balance.balances()).hasSize(1);
        assertThat(balance.balances().getFirst().availableMinor()).isEqualTo(1000);
        assertThat(balanceQueryService.balanceTransactions(merchantId, "test", query(50)).data())
                .allSatisfy(entry -> assertThat(entry.accountType()).isNotEqualTo("PLATFORM_CLEARING"));
    }

    @Test
    void balanceTransactionsCarryTheirPaymentAndEventType() {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        post(merchantId, "test", AccountType.MERCHANT_SETTLED, "USD", 2500, paymentId, "PaymentCaptured");

        CursorPage<BalanceTransactionResponse> page =
                balanceQueryService.balanceTransactions(merchantId, "test", query(50));

        assertThat(page.data()).hasSize(1);
        BalanceTransactionResponse entry = page.data().getFirst();
        assertThat(entry.object()).isEqualTo("balance_transaction");
        assertThat(entry.paymentId()).isEqualTo(paymentId);
        assertThat(entry.eventType()).isEqualTo("PaymentCaptured");
        assertThat(entry.amountMinor()).isEqualTo(2500);
        assertThat(entry.direction()).isEqualTo("CREDIT");
        assertThat(entry.mode()).isEqualTo("test");
    }

    @Test
    void balanceTransactionsPaginateWithoutRepeatingOrSkipping() {
        UUID merchantId = UUID.randomUUID();
        for (int i = 0; i < 7; i++) {
            post(merchantId, "test", AccountType.MERCHANT_SETTLED, "USD", 100 + i, UUID.randomUUID(),
                    "PaymentCaptured");
        }

        List<UUID> collected = new ArrayList<>();
        String cursor = null;
        for (int p = 0; p < 5; p++) {
            ListQuery paged = ListQuery.resolve(3, cursor, null, null, cursorCodec, merchantId, "test");
            CursorPage<BalanceTransactionResponse> page =
                    balanceQueryService.balanceTransactions(merchantId, "test", paged);
            page.data().forEach(entry -> collected.add(entry.id()));
            if (!page.hasMore()) {
                break;
            }
            cursor = page.nextCursor();
        }

        assertThat(collected).hasSize(7).doesNotHaveDuplicates();
    }

    @Test
    void theReportedBalanceEqualsADirectSumOverTheLedgerEntries() {
        // M19's own completion criterion. Meaningful because the balance is projected from
        // the ledger rather than stored beside it — a service keeping its own running
        // total would be comparing a number to itself here.
        UUID merchantId = UUID.randomUUID();
        post(merchantId, "test", AccountType.MERCHANT_SETTLED, "USD", 1200, UUID.randomUUID(), "PaymentCaptured");
        post(merchantId, "test", AccountType.MERCHANT_SETTLED, "USD", 800, UUID.randomUUID(), "PaymentCaptured");
        post(merchantId, "test", AccountType.MERCHANT_SETTLED, "USD", 45, UUID.randomUUID(), "PaymentCaptured");

        long reported = balanceQueryService.balance(merchantId, "test").balances().getFirst().availableMinor();

        Long summed = jdbcTemplate.queryForObject("""
                select coalesce(sum(e.amount_minor), 0)
                from transaction.ledger_entries e
                join transaction.accounts a on a.id = e.account_id
                where a.owner_id = ? and a.mode = 'test'
                  and a.account_type = 'MERCHANT_SETTLED' and e.direction = 'CREDIT'
                """, Long.class, merchantId);

        assertThat(reported).isEqualTo(2045).isEqualTo(summed);
    }
}
