package com.paymentflow.transaction.repository;

import com.paymentflow.transaction.domain.LedgerTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {

    List<LedgerTransaction> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);

    /**
     * The journals behind a page of ledger entries (M19.4) — one query for the page, so
     * the payment id and event type each entry reports cost nothing per row.
     */
    List<LedgerTransaction> findByIdIn(Collection<UUID> ids);
}
