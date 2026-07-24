package com.paymentflow.sandbox.engine;

import com.paymentflow.sandbox.domain.DecisionSource;
import com.paymentflow.sandbox.domain.Operation;
import com.paymentflow.sandbox.domain.OverrideScenario;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Advises on one operation's outcome: override → test card → mode default, in that
 * precedence order (§8.2/M17 task 3) — a pure function of its inputs, with no
 * persistence, no I/O, and no notion of override expiry (that's the override service's
 * job, M17.5). {@code decide} never mutates anything and never throws for a
 * missing/unknown card — an unrecognised or absent token simply falls through to the
 * mode default, exactly like no token at all (§5.4's backward-compatibility property).
 *
 * <p>The platform's maximum injectable latency (see {@code test_cards.chk_test_cards_
 * latency_bounded} and the eventual override bound, §8 Security) is what
 * {@link OverrideScenario#FORCE_TIMEOUT} uses, regardless of any configured value — a
 * merchant forcing a timeout shouldn't need to know the platform's own timeout budget
 * for it to reliably fire.
 *
 * <p><b>{@link #decide} is the test-mode entry point only.</b> Live mode calls
 * {@link #decideLive} instead, which has no card/override parameters at all — not
 * merely a code path that chooses to ignore them, but a method signature that makes it
 * a compile error to pass developer-controllable state into a live decision at all.
 * This is §7's mode-isolation guarantee enforced one level stronger than "the engine
 * behaves correctly": the caller-side wiring ({@code SandboxDecisionService}) cannot
 * even look up a live merchant's override or a card for a live decision, because there
 * is no method that would accept them.
 */
@Component
public class DecisionEngine {

    /** The platform-wide ceiling on injected latency (matches the schema-enforced bound on both test cards and overrides). */
    public static final int MAX_INJECTABLE_LATENCY_MS = 10_000;

    private static final String ACQUIRER_DECLINE_CODE = "card_declined";
    private static final String ACQUIRER_ERROR_CODE = "processing_error";

    private final SimulatedAcquirerProperties simulatedAcquirerProperties;

    public DecisionEngine(SimulatedAcquirerProperties simulatedAcquirerProperties) {
        this.simulatedAcquirerProperties = simulatedAcquirerProperties;
    }

    /**
     * Live mode's decision (D104, M17.7) — a small stochastic decline rate, a
     * realistic latency distribution, and an occasional transient error, none of it
     * developer-controllable (§7): unlike every {@link #decide} branch, this method has
     * no card/override parameter at all, so there is structurally nothing for a
     * merchant's own input to influence. {@code outcomeDraw}/{@code latencyDraw} are
     * uniform {@code [0, 1)} values the caller supplies (D103/M17.2's "no I/O, no
     * hidden state" charter extended here: the engine stays a pure function of its
     * inputs even though live mode is stochastic — {@code SandboxDecisionService} owns
     * the actual random source, this method owns only the distribution shape, so
     * {@code decideLive(operation, 0.5, 0.5)} is exhaustively, deterministically
     * testable exactly like every other branch in this class).
     */
    public EngineDecision decideLive(Operation operation, double outcomeDraw, double latencyDraw) {
        int latencyMs = sampleLatencyMs(latencyDraw);
        if (outcomeDraw < simulatedAcquirerProperties.declineRate()) {
            return EngineDecision.decline(ACQUIRER_DECLINE_CODE, DecisionSource.ACQUIRER, latencyMs);
        }
        if (outcomeDraw < simulatedAcquirerProperties.declineRate() + simulatedAcquirerProperties.errorRate()) {
            return EngineDecision.error(ACQUIRER_ERROR_CODE, DecisionSource.ACQUIRER, latencyMs);
        }
        return EngineDecision.approve(DecisionSource.ACQUIRER, latencyMs);
    }

    /**
     * A uniform spread of {@code [mean - stdDev, mean + stdDev]}, not a true Gaussian —
     * "realistic" here means "a small, non-zero delay a real acquirer call would have,"
     * not a statistically rigorous model, and a uniform spread keeps this exhaustively
     * testable with plain boundary values (0.0/0.5/1.0) like the rest of this class.
     * Clamped to the same platform-wide ceiling every injected latency respects.
     */
    private int sampleLatencyMs(double latencyDraw) {
        int spread = (int) Math.round((latencyDraw - 0.5) * 2 * simulatedAcquirerProperties.latencyStdDevMs());
        int latencyMs = simulatedAcquirerProperties.latencyMeanMs() + spread;
        return Math.clamp(latencyMs, 0, MAX_INJECTABLE_LATENCY_MS);
    }

    public EngineDecision decide(Operation operation, Optional<TestCardProfile> card, Optional<OverrideSnapshot> override) {
        Optional<OverrideSnapshot> applicable = override.filter(o -> appliesTo(o.scenario(), operation));
        return switch (operation) {
            case AUTHORIZE -> decideAuthorize(applicable, card);
            case CAPTURE -> decideCapture(applicable, card);
            case REFUND -> decideRefund(card);
        };
    }

    private EngineDecision decideAuthorize(Optional<OverrideSnapshot> override, Optional<TestCardProfile> card) {
        if (override.isPresent()) {
            return switch (override.get().scenario()) {
                case FORCE_DECLINE -> EngineDecision.decline(override.get().declineCode(), DecisionSource.OVERRIDE, 0);
                case FORCE_ERROR -> EngineDecision.error(override.get().errorCode(), DecisionSource.OVERRIDE, 0);
                case INJECT_LATENCY -> EngineDecision.approve(DecisionSource.OVERRIDE, valueOrZero(override.get()));
                case FORCE_TIMEOUT -> EngineDecision.error("processing_error", DecisionSource.OVERRIDE, MAX_INJECTABLE_LATENCY_MS);
                case FORCE_RATE_LIMIT -> EngineDecision.error("rate_limited", DecisionSource.OVERRIDE, 0);
                case DELAY_SETTLEMENT -> throw new IllegalStateException("DELAY_SETTLEMENT does not apply to AUTHORIZE");
            };
        }
        if (card.isPresent()) {
            TestCardProfile profile = card.get();
            return switch (profile.outcome()) {
                case APPROVE -> EngineDecision.approve(DecisionSource.TEST_CARD, profile.latencyMs());
                case DECLINE -> EngineDecision.decline(profile.declineCode(), DecisionSource.TEST_CARD, profile.latencyMs());
                case ERROR -> EngineDecision.error(profile.errorCode(), DecisionSource.TEST_CARD, profile.latencyMs());
                case REQUIRE_ACTION -> EngineDecision.requireAction(DecisionSource.TEST_CARD, profile.latencyMs());
                // No seeded card uses DELAY today (§ test-card catalogue) — reserved vocabulary
                // for a future authorize-time-deferred scenario (see DecisionOutcome's javadoc).
                case DELAY -> EngineDecision.deferred(Operation.AUTHORIZE, profile.deferredDelayMs(), DecisionSource.TEST_CARD);
            };
        }
        return modeDefault();
    }

    private EngineDecision decideCapture(Optional<OverrideSnapshot> override, Optional<TestCardProfile> card) {
        if (override.isPresent() && override.get().scenario() == OverrideScenario.DELAY_SETTLEMENT) {
            return EngineDecision.deferred(Operation.CAPTURE, valueOrZero(override.get()), DecisionSource.OVERRIDE);
        }
        if (card.isPresent()) {
            TestCardProfile profile = card.get();
            return switch (profile.captureBehaviour()) {
                case SUCCEED -> EngineDecision.approve(DecisionSource.TEST_CARD, profile.latencyMs());
                case FAIL -> EngineDecision.error("capture_failed", DecisionSource.TEST_CARD, profile.latencyMs());
                case DEFER -> EngineDecision.deferred(Operation.CAPTURE, profile.deferredDelayMs(), DecisionSource.TEST_CARD);
            };
        }
        return modeDefault();
    }

    private EngineDecision decideRefund(Optional<TestCardProfile> card) {
        // No override scenario targets refund (§8.2's vocabulary is authorize/capture only).
        if (card.isPresent()) {
            TestCardProfile profile = card.get();
            return switch (profile.refundBehaviour()) {
                case SUCCEED -> EngineDecision.approve(DecisionSource.TEST_CARD, profile.latencyMs());
                case FAIL -> EngineDecision.error("refund_failed", DecisionSource.TEST_CARD, profile.latencyMs());
            };
        }
        return modeDefault();
    }

    /**
     * Test and live share this path in M17.2–M17.6: live's simulated acquirer (D104,
     * M17.7) replaces only this branch's live behaviour with a stochastic distribution
     * — every other precedence step (override, test card) is already unreachable in
     * live mode (§7's mode-isolation guarantee), so no other branch changes.
     */
    private EngineDecision modeDefault() {
        return EngineDecision.approve(DecisionSource.MODE_DEFAULT, 0);
    }

    private static int valueOrZero(OverrideSnapshot override) {
        return override.valueMs() == null ? 0 : override.valueMs();
    }

    private static boolean appliesTo(OverrideScenario scenario, Operation operation) {
        return switch (scenario) {
            case FORCE_DECLINE, FORCE_ERROR, INJECT_LATENCY, FORCE_TIMEOUT, FORCE_RATE_LIMIT -> operation == Operation.AUTHORIZE;
            case DELAY_SETTLEMENT -> operation == Operation.CAPTURE;
        };
    }
}
