package com.paymentflow.merchant.repository;

import com.paymentflow.merchant.domain.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    Optional<ApiKey> findByKeyHash(String keyHash);

    Optional<ApiKey> findByMerchantIdAndRevokedAtIsNull(UUID merchantId);
}
