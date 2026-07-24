package com.paymentflow.sandbox.service;

import com.paymentflow.common.exception.BadRequestException;
import com.paymentflow.sandbox.domain.DecisionLogEntry;
import com.paymentflow.sandbox.domain.DecisionOutcome;
import com.paymentflow.sandbox.domain.DecisionSource;
import com.paymentflow.sandbox.domain.Operation;
import com.paymentflow.sandbox.domain.SimulationOverride;
import com.paymentflow.sandbox.dto.SandboxDecisionRequest;
import com.paymentflow.sandbox.dto.SandboxDecisionResponse;
import com.paymentflow.sandbox.engine.DecisionEngine;
import com.paymentflow.sandbox.engine.EngineDecision;
import com.paymentflow.sandbox.engine.OverrideSnapshot;
import com.paymentflow.sandbox.engine.TestCardProfile;
import com.paymentflow.sandbox.repository.DecisionLogRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates one advisory decision (§4.2): decision-key replay (D128) → mode-gated
 * evaluation (live never reaches the card/override lookup at all, §7) →
 * {@link DecisionEngine} → append to {@code decision_log}. Never mutates a payment,
 * never publishes an event (D103) — this is the entire boundary of what
 * sandbox-service does to answer a request.
 */
@Service
public class SandboxDecisionService {

    private static final String LIVE_MODE = "live";

    private final DecisionLogRepository decisionLogRepository;
    private final TestCardService testCardService;
    private final DecisionEngine decisionEngine;
    private final OverrideService overrideService;

    public SandboxDecisionService(DecisionLogRepository decisionLogRepository, TestCardService testCardService,
                                  DecisionEngine decisionEngine, OverrideService overrideService) {
        this.decisionLogRepository = decisionLogRepository;
        this.testCardService = testCardService;
        this.decisionEngine = decisionEngine;
        this.overrideService = overrideService;
    }

    @Transactional
    public SandboxDecisionResponse decide(UUID merchantId, String mode, SandboxDecisionRequest request,
                                          String correlationId) {
        Optional<DecisionLogEntry> existing = decisionLogRepository.findByDecisionKey(request.decisionKey());
        if (existing.isPresent()) {
            return toResponse(existing.get());
        }

        Operation operation = parseOperation(request.operation());
        Evaluation evaluation = evaluate(merchantId, mode, operation, request.paymentMethodToken());
        EngineDecision decision = evaluation.decision();

        DecisionLogEntry entry = DecisionLogEntry.of(request.decisionKey(), merchantId, mode, request.paymentId(),
                operation, request.paymentMethodToken(), request.amountMinor(), request.currency(),
                decision.outcome(), decision.declineCode(), decision.errorCode(), decision.latencyMs(),
                decision.source(), evaluation.overrideId(), decision.deferredOperation(), decision.deferredDelayMs(),
                correlationId);

        DecisionLogEntry saved = insertOrFindExisting(entry, request.decisionKey());
        return toResponse(saved);
    }

    /**
     * Live mode never reaches {@link TestCardService} or the override lookup at all
     * (§7 barrier ④) — {@link DecisionEngine#decideLive} has no parameter that could
     * accept them, so this isn't a runtime check, it's a method that structurally
     * cannot be handed developer-controllable state.
     *
     * <p>M17.5: the active override (if any) is consumed (D127) only when the engine's
     * returned {@code source} is actually {@code OVERRIDE} — the engine's own
     * override-applies-to-this-operation check (§8.2's precedence, e.g. an
     * authorize-only override set while a REFUND is being decided) is the single source
     * of truth for whether the override was really used, so this never double-guesses it.
     */
    private Evaluation evaluate(UUID merchantId, String mode, Operation operation, String paymentMethodToken) {
        if (LIVE_MODE.equals(mode)) {
            return new Evaluation(decisionEngine.decideLive(operation), null);
        }
        Optional<TestCardProfile> card = testCardService.findActive(paymentMethodToken).map(TestCardProfile::of);
        Optional<SimulationOverride> activeOverride = overrideService.findActive(merchantId, mode);
        Optional<OverrideSnapshot> engineOverride = activeOverride.flatMap(SandboxDecisionService::toEngineSnapshot);

        EngineDecision decision = decisionEngine.decide(operation, card, engineOverride);
        UUID consumedOverrideId = null;
        if (decision.source() == DecisionSource.OVERRIDE && activeOverride.isPresent()) {
            SimulationOverride override = activeOverride.get();
            overrideService.consume(override.getId());
            consumedOverrideId = override.getId();
        }
        return new Evaluation(decision, consumedOverrideId);
    }

    private static Optional<OverrideSnapshot> toEngineSnapshot(SimulationOverride override) {
        return override.getScenario().toEngineScenario()
                .map(engineScenario -> new OverrideSnapshot(
                        engineScenario, override.getDeclineCode(), override.getErrorCode(), override.getLatencyMs()));
    }

    private record Evaluation(EngineDecision decision, UUID overrideId) {
    }

    /**
     * Handles the genuine race of two concurrent calls sharing a decision key (D128):
     * the loser's insert violates the unique constraint, and it simply reads back the
     * winner's row rather than erroring — whichever decision reached the table first is
     * authoritative for that key.
     */
    private DecisionLogEntry insertOrFindExisting(DecisionLogEntry entry, String decisionKey) {
        try {
            return decisionLogRepository.save(entry);
        } catch (DataIntegrityViolationException raceLost) {
            return decisionLogRepository.findByDecisionKey(decisionKey).orElseThrow(() -> raceLost);
        }
    }

    private static Operation parseOperation(String raw) {
        try {
            return Operation.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown operation: " + raw);
        }
    }

    private static SandboxDecisionResponse toResponse(DecisionLogEntry entry) {
        DecisionOutcome outcome = entry.getOutcome();
        DecisionSource source = entry.getSource();
        Operation deferredOperation = entry.getDeferredOperation();
        return new SandboxDecisionResponse(
                outcome.name(),
                entry.getDeclineCode(),
                entry.getErrorCode(),
                entry.getLatencyMs(),
                source.name(),
                deferredOperation == null ? null : deferredOperation.name(),
                entry.getDeferredDelayMs());
    }
}
