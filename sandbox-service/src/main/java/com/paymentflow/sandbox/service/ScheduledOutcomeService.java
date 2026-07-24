package com.paymentflow.sandbox.service;

import com.paymentflow.sandbox.domain.DecisionOutcome;
import com.paymentflow.sandbox.domain.Operation;
import com.paymentflow.sandbox.domain.ScheduledOutcome;
import com.paymentflow.sandbox.repository.ScheduledOutcomeRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Writes a {@link ScheduledOutcome} row (M17.6) — always called from within
 * {@code SandboxDecisionService}'s own {@code @Transactional} method, so the row
 * commits atomically with the decision it was scheduled from. No read/consume methods
 * here: only {@link ScheduledOutcomeRelay} ever reads pending rows, via its own
 * repository query.
 */
@Service
public class ScheduledOutcomeService {

    private final ScheduledOutcomeRepository repository;

    public ScheduledOutcomeService(ScheduledOutcomeRepository repository) {
        this.repository = repository;
    }

    public void schedule(UUID paymentId, UUID merchantId, String mode, Operation operation, DecisionOutcome outcome,
                         int delayMs) {
        Instant fireAt = Instant.now().plusMillis(delayMs);
        repository.save(ScheduledOutcome.create(paymentId, merchantId, mode, operation, outcome, fireAt));
    }
}
