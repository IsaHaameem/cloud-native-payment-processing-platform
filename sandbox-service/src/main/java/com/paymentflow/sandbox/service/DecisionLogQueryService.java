package com.paymentflow.sandbox.service;

import com.paymentflow.sandbox.domain.DecisionLogEntry;
import com.paymentflow.sandbox.repository.DecisionLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Read access to a merchant's own decision history (§4.2/M17.8) — always scoped to the
 * caller's verified merchant/mode, mirroring every other query in this service.
 */
@Service
public class DecisionLogQueryService {

    private final DecisionLogRepository decisionLogRepository;

    public DecisionLogQueryService(DecisionLogRepository decisionLogRepository) {
        this.decisionLogRepository = decisionLogRepository;
    }

    public Page<DecisionLogEntry> list(UUID merchantId, String mode, Pageable pageable) {
        return decisionLogRepository.findByMerchantIdAndModeOrderByCreatedAtDesc(merchantId, mode, pageable);
    }

    public List<DecisionLogEntry> forPayment(UUID merchantId, String mode, UUID paymentId) {
        return decisionLogRepository.findByMerchantIdAndModeAndPaymentIdOrderByCreatedAtDesc(
                merchantId, mode, paymentId);
    }
}
