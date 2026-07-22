package com.paymentflow.merchant.repository;

import com.paymentflow.merchant.domain.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    Optional<ApiKey> findByKeyHash(String keyHash);

    List<ApiKey> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId);

    Optional<ApiKey> findByIdAndMerchantId(UUID id, UUID merchantId);
}
