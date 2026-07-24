package com.paymentflow.sandbox.service;

import com.paymentflow.common.exception.BadRequestException;
import com.paymentflow.common.exception.ConflictException;
import com.paymentflow.common.exception.ForbiddenException;
import com.paymentflow.sandbox.domain.SimulationOverride;
import com.paymentflow.sandbox.domain.SimulationScenario;
import com.paymentflow.sandbox.repository.SimulationOverrideRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns the "chaos knob" (§8.2, M17.5): creating an override (superseding whatever was
 * previously active for the same merchant/mode), finding the currently active one for
 * {@code SandboxDecisionService} to hand the engine, revoking one early, and consuming
 * one atomically when it actually applies to a decision (D127). Never reasons about
 * webhook delivery itself — {@link SimulationScenario#toEngineScenario()} is what keeps
 * the two webhook-path scenarios out of {@code DecisionEngine} entirely (D131).
 */
@Service
public class OverrideService {

    private static final String TEST_MODE = "test";
    private static final int MAX_LATENCY_MS = 10_000;

    private final SimulationOverrideRepository repository;
    private final Clock clock = Clock.systemUTC();

    public OverrideService(SimulationOverrideRepository repository) {
        this.repository = repository;
    }

    /**
     * Rejects a live-mode caller outright (§7 — only test mode is developer-controllable;
     * the schema's own {@code chk_simulation_overrides_mode} would reject the insert
     * anyway, this is the honest error message instead of a raw constraint violation).
     */
    @Transactional
    public SimulationOverride create(UUID merchantId, String mode, SimulationScenario scenario, String declineCode,
                                     String errorCode, Integer latencyMs, Integer remainingCount,
                                     Integer durationSeconds) {
        if (!TEST_MODE.equals(mode)) {
            throw new ForbiddenException("Simulation overrides are only available in test mode.");
        }
        validate(scenario, declineCode, errorCode, latencyMs, remainingCount, durationSeconds);

        Instant now = clock.instant();
        repository.revokeActive(merchantId, mode, now);
        Instant expiresAt = durationSeconds == null ? null : now.plusSeconds(durationSeconds);
        SimulationOverride override = SimulationOverride.create(
                merchantId, mode, scenario, declineCode, errorCode, latencyMs, remainingCount, expiresAt);
        try {
            return repository.save(override);
        } catch (DataIntegrityViolationException concurrentCreate) {
            throw new ConflictException(
                    "An active simulation override was just set concurrently for this merchant and mode.");
        }
    }

    /** The currently active override for (merchantId, mode), or empty if none is in effect right now. */
    public Optional<SimulationOverride> findActive(UUID merchantId, String mode) {
        Instant now = clock.instant();
        return repository.findByMerchantIdAndModeAndRevokedAtIsNull(merchantId, mode)
                .filter(override -> override.isActive(now));
    }

    @Transactional
    public void revokeActive(UUID merchantId, String mode) {
        repository.revokeActive(merchantId, mode, clock.instant());
    }

    /** Consumes one use of a count-bounded override; a no-op for one with no count bound (D127). */
    @Transactional
    public void consume(UUID overrideId) {
        repository.decrementRemainingCount(overrideId);
    }

    private static void validate(SimulationScenario scenario, String declineCode, String errorCode,
                                 Integer latencyMs, Integer remainingCount, Integer durationSeconds) {
        if (remainingCount == null && durationSeconds == null) {
            throw new BadRequestException("An override needs a remainingCount, a durationSeconds, or both.");
        }
        if (remainingCount != null && remainingCount <= 0) {
            throw new BadRequestException("remainingCount must be positive.");
        }
        if (durationSeconds != null && durationSeconds <= 0) {
            throw new BadRequestException("durationSeconds must be positive.");
        }
        if (scenario == SimulationScenario.FORCE_DECLINE && isBlank(declineCode)) {
            throw new BadRequestException("force_decline requires a declineCode.");
        }
        if (scenario == SimulationScenario.FORCE_ERROR && isBlank(errorCode)) {
            throw new BadRequestException("force_error requires an errorCode.");
        }
        if ((scenario == SimulationScenario.INJECT_LATENCY || scenario == SimulationScenario.DELAY_SETTLEMENT)
                && latencyMs == null) {
            throw new BadRequestException(scenario.name().toLowerCase(Locale.ROOT) + " requires latencyMs.");
        }
        if (latencyMs != null && (latencyMs < 0 || latencyMs > MAX_LATENCY_MS)) {
            throw new BadRequestException("latencyMs must be between 0 and " + MAX_LATENCY_MS + ".");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
