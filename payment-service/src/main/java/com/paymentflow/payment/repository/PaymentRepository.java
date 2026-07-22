package com.paymentflow.payment.repository;

import com.paymentflow.payment.domain.Payment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdAndMerchantIdAndMode(UUID id, UUID merchantId, String mode);

    Page<Payment> findByMerchantIdAndMode(UUID merchantId, String mode, Pageable pageable);
}
