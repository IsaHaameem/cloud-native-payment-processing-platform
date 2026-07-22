package com.paymentflow.analytics.repository;

import com.paymentflow.analytics.domain.MerchantPaymentStats;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MerchantPaymentStatsRepository extends JpaRepository<MerchantPaymentStats, UUID> {

    Optional<MerchantPaymentStats> findByMerchantIdAndCurrencyAndMode(UUID merchantId, String currency, String mode);
}
