package com.paymentflow.transaction.repository;

import com.paymentflow.transaction.domain.Account;
import com.paymentflow.transaction.domain.AccountType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByAccountTypeAndOwnerIdAndCurrency(AccountType accountType, UUID ownerId, String currency);
}
