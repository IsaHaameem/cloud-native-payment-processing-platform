package com.paymentflow.transaction.service;

import com.paymentflow.common.dto.page.CursorPage;
import com.paymentflow.common.query.Cursor;
import com.paymentflow.common.query.CursorCodec;
import com.paymentflow.common.query.ListQuery;
import com.paymentflow.transaction.domain.Account;
import com.paymentflow.transaction.domain.AccountType;
import com.paymentflow.transaction.domain.LedgerEntry;
import com.paymentflow.transaction.domain.LedgerTransaction;
import com.paymentflow.transaction.dto.BalanceResponse;
import com.paymentflow.transaction.dto.BalanceTransactionResponse;
import com.paymentflow.transaction.repository.AccountRepository;
import com.paymentflow.transaction.repository.LedgerEntryRepository;
import com.paymentflow.transaction.repository.LedgerTransactionRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * transaction-service's only read surface, and its first application entry point that is
 * not a Kafka listener (M19.4).
 *
 * <p><b>Read-only by construction, not by convention.</b> D42's boundary — "the ledger is
 * only ever written by the Kafka consumer" — is preserved by this class holding nothing
 * that could write one: no {@code LedgerService}, no {@code TransactionTemplate}, no
 * account mutation. M19's risk table flags giving this service a web layer as the
 * milestone's highest-risk change; the mitigation is that the write path is unreachable
 * from here, not merely that no endpoint currently calls it.
 *
 * <p>Balances are <em>projected from the ledger</em> rather than read from a separate
 * running total, which is what makes M19's "ledger totals match a direct psql sum
 * exactly" criterion a property of the design rather than a coincidence to be checked.
 */
@Service
@Transactional(readOnly = true)
public class BalanceQueryService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final LedgerTransactionRepository ledgerTransactionRepository;
    private final CursorCodec cursorCodec;

    public BalanceQueryService(AccountRepository accountRepository, LedgerEntryRepository ledgerEntryRepository,
                               LedgerTransactionRepository ledgerTransactionRepository, CursorCodec cursorCodec) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.ledgerTransactionRepository = ledgerTransactionRepository;
        this.cursorCodec = cursorCodec;
    }

    /** Pending and available per currency, derived from the merchant's own ledger accounts. */
    public BalanceResponse balance(UUID merchantId, String mode) {
        Map<String, long[]> byCurrency = new LinkedHashMap<>();
        for (Account account : accountRepository.findByOwnerIdAndMode(merchantId, mode)) {
            long[] slot = byCurrency.computeIfAbsent(account.getCurrency(), currency -> new long[2]);
            if (account.getAccountType() == AccountType.MERCHANT_PENDING) {
                slot[0] = account.getBalanceMinor();
            } else if (account.getAccountType() == AccountType.MERCHANT_SETTLED) {
                slot[1] = account.getBalanceMinor();
            }
        }

        List<BalanceResponse.CurrencyBalance> balances = byCurrency.entrySet().stream()
                .map(entry -> new BalanceResponse.CurrencyBalance(
                        entry.getKey(), entry.getValue()[0], entry.getValue()[1]))
                // Sorted so two calls return the same order — an unordered balance list
                // makes a merchant's own diff of two responses noisy for no reason.
                .sorted(Comparator.comparing(BalanceResponse.CurrencyBalance::currency))
                .toList();

        // A merchant with no ledger activity gets an empty list, not a 404: having no
        // balance is a fact about them, not a missing resource.
        return new BalanceResponse(BalanceResponse.OBJECT_TYPE, balances);
    }

    public CursorPage<BalanceTransactionResponse> balanceTransactions(UUID merchantId, String mode, ListQuery query) {
        List<Account> accounts = accountRepository.findByOwnerIdAndMode(merchantId, mode);
        if (accounts.isEmpty()) {
            return CursorPage.empty();
        }
        List<UUID> accountIds = accounts.stream().map(Account::getId).toList();
        Map<UUID, AccountType> accountTypes = accounts.stream()
                .collect(Collectors.toMap(Account::getId, Account::getAccountType));

        // Sentinels rather than nulls — see LedgerEntryRepository.findPage for why an
        // untyped null bind is rejected by Postgres outright, and why the guarded form
        // would cost the index condition as well.
        List<LedgerEntry> fetched = ledgerEntryRepository.findPage(accountIds, mode,
                query.createdAfterBound(), query.createdBeforeBound(),
                query.cursorCreatedAtBound(), query.cursorIdBound(),
                Limit.of(query.fetchSize()));

        CursorPage<LedgerEntry> page = CursorPage.of(fetched, query.limit(),
                entry -> cursorCodec.encode(new Cursor(entry.getCreatedAt(), entry.getId(), merchantId, mode)));

        // One query for the page's journals, so the payment id and event type each entry
        // reports cost nothing per row.
        Map<UUID, LedgerTransaction> journals = page.data().isEmpty()
                ? Map.of()
                : ledgerTransactionRepository.findByIdIn(page.data().stream()
                        .map(LedgerEntry::getLedgerTransactionId).distinct().toList()).stream()
                        .collect(Collectors.toMap(LedgerTransaction::getId, Function.identity()));

        List<BalanceTransactionResponse> data = new ArrayList<>(page.data().size());
        for (LedgerEntry entry : page.data()) {
            LedgerTransaction journal = journals.get(entry.getLedgerTransactionId());
            data.add(new BalanceTransactionResponse(
                    entry.getId(),
                    BalanceTransactionResponse.OBJECT_TYPE,
                    journal == null ? null : journal.getPaymentId(),
                    journal == null ? null : journal.getEventType(),
                    accountTypes.get(entry.getAccountId()).name(),
                    entry.getDirection().name(),
                    entry.getAmountMinor(),
                    entry.getCurrency(),
                    entry.getMode(),
                    entry.getCreatedAt()));
        }
        return new CursorPage<>(CursorPage.OBJECT_TYPE, data, page.hasMore(), page.nextCursor());
    }
}
