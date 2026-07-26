package com.paymentflow.transaction.repository;

import com.paymentflow.transaction.domain.LedgerEntry;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByAccountId(UUID accountId);

    List<LedgerEntry> findByLedgerTransactionId(UUID ledgerTransactionId);

    /**
     * The balance-transactions list (M19.4): the merchant's own ledger legs, newest
     * first, keyset-paginated.
     *
     * <p>Scoped by {@code accountIds} — resolved from the merchant's own accounts before
     * this is called — rather than by a merchant column, because a ledger entry has no
     * owner of its own: it belongs to an account, and the account belongs to the
     * merchant. Passing the resolved set makes the scoping explicit at the call site and
     * keeps the platform's clearing account structurally unreachable, since a null-owner
     * account is never in that set.
     *
     * <p><b>Sentinel bounds rather than {@code :param is null} guards</b> — see
     * {@link com.paymentflow.common.query.ListQuery#EARLIEST}, where the constants now
     * live. Originally a correctness fix (Postgres cannot infer a bind parameter's type
     * from {@code ? is null} alone and rejects the statement outright), and M19.8's
     * {@code EXPLAIN} pass showed it is also what keeps the bounds inside the index
     * condition rather than demoting them to a post-scan filter.
     *
     * <p><b>The ordering is index-backed by
     * {@code idx_ledger_entries_account_created}</b>, added in M19.8 after a plan showed
     * this query reading every entry a merchant had ever accumulated and top-N sorting
     * it, once per page. On an account with 200k entries that was 5,962 buffers and 24 ms;
     * with the index it is 19 buffers and well under a millisecond. The pre-existing
     * {@code idx_ledger_entries_account_id} could locate the rows but could not order
     * them, and a keyset page that has to sort its whole input is a keyset page in name
     * only.
     */
    @Query("""
            select e from LedgerEntry e
            where e.accountId in :accountIds
              and e.mode = :mode
              and e.createdAt >= :createdAfter
              and e.createdAt < :createdBefore
              and (e.createdAt < :cursorCreatedAt
                   or (e.createdAt = :cursorCreatedAt and e.id < :cursorId))
            order by e.createdAt desc, e.id desc
            """)
    List<LedgerEntry> findPage(@Param("accountIds") Collection<UUID> accountIds,
                               @Param("mode") String mode,
                               @Param("createdAfter") Instant createdAfter,
                               @Param("createdBefore") Instant createdBefore,
                               @Param("cursorCreatedAt") Instant cursorCreatedAt,
                               @Param("cursorId") UUID cursorId,
                               Limit limit);
}
