package com.paymentflow.transaction.repository;

import com.paymentflow.transaction.domain.Account;
import com.paymentflow.transaction.domain.AccountType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByAccountTypeAndOwnerIdAndCurrencyAndMode(AccountType accountType, UUID ownerId,
                                                                    String currency, String mode);

    /**
     * Every account belonging to one merchant in one mode (M19.4) — the balance read.
     * Scoped by owner *and* mode in the signature (D101); the platform's own
     * {@code PLATFORM_CLEARING} account has a null owner and so can never appear here,
     * which is what keeps a merchant from reading the platform's clearing balance.
     */
    List<Account> findByOwnerIdAndMode(UUID ownerId, String mode);
}
