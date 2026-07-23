package com.paymentflow.sandbox.engine;

import com.paymentflow.sandbox.domain.CaptureBehaviour;
import com.paymentflow.sandbox.domain.DecisionOutcome;
import com.paymentflow.sandbox.domain.DecisionSource;
import com.paymentflow.sandbox.domain.Operation;
import com.paymentflow.sandbox.domain.OverrideScenario;
import com.paymentflow.sandbox.domain.RefundBehaviour;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The full card × override × operation precedence matrix (§8.2/M17 task 3), as a pure
 * function with no Spring context — every case is a plain constructor call, exactly
 * the property the M17 design required of this class.
 */
class DecisionEngineTest {

    private final DecisionEngine engine = new DecisionEngine();

    // ── AUTHORIZE: no override ──────────────────────────────────────────────

    @Test
    void authorizeApproveCardApproves() {
        TestCardProfile card = card(DecisionOutcome.APPROVE, null, null, 0, CaptureBehaviour.SUCCEED, RefundBehaviour.SUCCEED, null);
        EngineDecision decision = engine.decide(Operation.AUTHORIZE, Optional.of(card), Optional.empty());

        assertThat(decision.outcome()).isEqualTo(DecisionOutcome.APPROVE);
        assertThat(decision.source()).isEqualTo(DecisionSource.TEST_CARD);
    }

    @Test
    void authorizeDeclineCardDeclinesWithItsCode() {
        TestCardProfile card = card(DecisionOutcome.DECLINE, "card_declined", null, 0, CaptureBehaviour.SUCCEED, RefundBehaviour.SUCCEED, null);
        EngineDecision decision = engine.decide(Operation.AUTHORIZE, Optional.of(card), Optional.empty());

        assertThat(decision.outcome()).isEqualTo(DecisionOutcome.DECLINE);
        assertThat(decision.declineCode()).isEqualTo("card_declined");
        assertThat(decision.source()).isEqualTo(DecisionSource.TEST_CARD);
    }

    @Test
    void authorizeErrorCardErrorsWithItsCode() {
        TestCardProfile card = card(DecisionOutcome.ERROR, null, "processing_error", 0, CaptureBehaviour.SUCCEED, RefundBehaviour.SUCCEED, null);
        EngineDecision decision = engine.decide(Operation.AUTHORIZE, Optional.of(card), Optional.empty());

        assertThat(decision.outcome()).isEqualTo(DecisionOutcome.ERROR);
        assertThat(decision.errorCode()).isEqualTo("processing_error");
    }

    @Test
    void authorizeRequireActionCard() {
        TestCardProfile card = card(DecisionOutcome.REQUIRE_ACTION, null, null, 0, CaptureBehaviour.SUCCEED, RefundBehaviour.SUCCEED, null);
        EngineDecision decision = engine.decide(Operation.AUTHORIZE, Optional.of(card), Optional.empty());

        assertThat(decision.outcome()).isEqualTo(DecisionOutcome.REQUIRE_ACTION);
    }

    @Test
    void authorizeDelayCardDefersTheAuthorizeItself() {
        TestCardProfile card = card(DecisionOutcome.DELAY, null, null, 0, CaptureBehaviour.SUCCEED, RefundBehaviour.SUCCEED, 3000);
        EngineDecision decision = engine.decide(Operation.AUTHORIZE, Optional.of(card), Optional.empty());

        assertThat(decision.deferredOperation()).isEqualTo(Operation.AUTHORIZE);
        assertThat(decision.deferredDelayMs()).isEqualTo(3000);
    }

    @Test
    void authorizeCardCarriesItsLatency() {
        TestCardProfile card = card(DecisionOutcome.APPROVE, null, null, 5000, CaptureBehaviour.SUCCEED, RefundBehaviour.SUCCEED, null);
        EngineDecision decision = engine.decide(Operation.AUTHORIZE, Optional.of(card), Optional.empty());

        assertThat(decision.latencyMs()).isEqualTo(5000);
    }

    @Test
    void authorizeNoTokenNoCardFallsToModeDefault() {
        EngineDecision decision = engine.decide(Operation.AUTHORIZE, Optional.empty(), Optional.empty());

        assertThat(decision.outcome()).isEqualTo(DecisionOutcome.APPROVE);
        assertThat(decision.source()).isEqualTo(DecisionSource.MODE_DEFAULT);
    }

    // ── AUTHORIZE: override precedence (override beats card) ───────────────

    @Test
    void authorizeForceDeclineOverrideBeatsAnApprovingCard() {
        TestCardProfile approvingCard = card(DecisionOutcome.APPROVE, null, null, 0, CaptureBehaviour.SUCCEED, RefundBehaviour.SUCCEED, null);
        OverrideSnapshot override = new OverrideSnapshot(OverrideScenario.FORCE_DECLINE, "insufficient_funds", null, null);

        EngineDecision decision = engine.decide(Operation.AUTHORIZE, Optional.of(approvingCard), Optional.of(override));

        assertThat(decision.outcome()).isEqualTo(DecisionOutcome.DECLINE);
        assertThat(decision.declineCode()).isEqualTo("insufficient_funds");
        assertThat(decision.source()).isEqualTo(DecisionSource.OVERRIDE);
    }

    @Test
    void authorizeForceErrorOverride() {
        OverrideSnapshot override = new OverrideSnapshot(OverrideScenario.FORCE_ERROR, null, "issuer_unavailable", null);
        EngineDecision decision = engine.decide(Operation.AUTHORIZE, Optional.empty(), Optional.of(override));

        assertThat(decision.outcome()).isEqualTo(DecisionOutcome.ERROR);
        assertThat(decision.errorCode()).isEqualTo("issuer_unavailable");
        assertThat(decision.source()).isEqualTo(DecisionSource.OVERRIDE);
    }

    @Test
    void authorizeInjectLatencyOverrideApprovesWithTheConfiguredDelay() {
        OverrideSnapshot override = new OverrideSnapshot(OverrideScenario.INJECT_LATENCY, null, null, 2500);
        EngineDecision decision = engine.decide(Operation.AUTHORIZE, Optional.empty(), Optional.of(override));

        assertThat(decision.outcome()).isEqualTo(DecisionOutcome.APPROVE);
        assertThat(decision.latencyMs()).isEqualTo(2500);
        assertThat(decision.source()).isEqualTo(DecisionSource.OVERRIDE);
    }

    @Test
    void authorizeForceTimeoutOverrideIgnoresItsOwnValueAndUsesThePlatformCeiling() {
        OverrideSnapshot override = new OverrideSnapshot(OverrideScenario.FORCE_TIMEOUT, null, null, 1);
        EngineDecision decision = engine.decide(Operation.AUTHORIZE, Optional.empty(), Optional.of(override));

        assertThat(decision.outcome()).isEqualTo(DecisionOutcome.ERROR);
        assertThat(decision.errorCode()).isEqualTo("processing_error");
        assertThat(decision.latencyMs()).isEqualTo(DecisionEngine.MAX_INJECTABLE_LATENCY_MS);
    }

    @Test
    void authorizeForceRateLimitOverride() {
        OverrideSnapshot override = new OverrideSnapshot(OverrideScenario.FORCE_RATE_LIMIT, null, null, null);
        EngineDecision decision = engine.decide(Operation.AUTHORIZE, Optional.empty(), Optional.of(override));

        assertThat(decision.outcome()).isEqualTo(DecisionOutcome.ERROR);
        assertThat(decision.errorCode()).isEqualTo("rate_limited");
    }

    @Test
    void authorizeDelaySettlementOverrideDoesNotApplyToAuthorizeFallsToCard() {
        TestCardProfile card = card(DecisionOutcome.APPROVE, null, null, 0, CaptureBehaviour.SUCCEED, RefundBehaviour.SUCCEED, null);
        OverrideSnapshot captureOnlyOverride = new OverrideSnapshot(OverrideScenario.DELAY_SETTLEMENT, null, null, 5000);

        EngineDecision decision = engine.decide(Operation.AUTHORIZE, Optional.of(card), Optional.of(captureOnlyOverride));

        assertThat(decision.source()).isEqualTo(DecisionSource.TEST_CARD);
        assertThat(decision.deferredOperation()).isNull();
    }

    // ── CAPTURE ──────────────────────────────────────────────────────────

    @Test
    void captureSucceedCardApproves() {
        TestCardProfile card = card(DecisionOutcome.APPROVE, null, null, 0, CaptureBehaviour.SUCCEED, RefundBehaviour.SUCCEED, null);
        EngineDecision decision = engine.decide(Operation.CAPTURE, Optional.of(card), Optional.empty());

        assertThat(decision.outcome()).isEqualTo(DecisionOutcome.APPROVE);
        assertThat(decision.source()).isEqualTo(DecisionSource.TEST_CARD);
    }

    @Test
    void captureFailCardErrors() {
        TestCardProfile card = card(DecisionOutcome.APPROVE, null, null, 0, CaptureBehaviour.FAIL, RefundBehaviour.SUCCEED, null);
        EngineDecision decision = engine.decide(Operation.CAPTURE, Optional.of(card), Optional.empty());

        assertThat(decision.outcome()).isEqualTo(DecisionOutcome.ERROR);
        assertThat(decision.errorCode()).isEqualTo("capture_failed");
    }

    @Test
    void captureDeferCardDefersCapture() {
        TestCardProfile card = card(DecisionOutcome.APPROVE, null, null, 0, CaptureBehaviour.DEFER, RefundBehaviour.SUCCEED, 5000);
        EngineDecision decision = engine.decide(Operation.CAPTURE, Optional.of(card), Optional.empty());

        assertThat(decision.deferredOperation()).isEqualTo(Operation.CAPTURE);
        assertThat(decision.deferredDelayMs()).isEqualTo(5000);
        assertThat(decision.source()).isEqualTo(DecisionSource.TEST_CARD);
    }

    @Test
    void captureDelaySettlementOverrideBeatsAnEvenSucceedingCard() {
        TestCardProfile succeedingCard = card(DecisionOutcome.APPROVE, null, null, 0, CaptureBehaviour.SUCCEED, RefundBehaviour.SUCCEED, null);
        OverrideSnapshot override = new OverrideSnapshot(OverrideScenario.DELAY_SETTLEMENT, null, null, 7000);

        EngineDecision decision = engine.decide(Operation.CAPTURE, Optional.of(succeedingCard), Optional.of(override));

        assertThat(decision.deferredOperation()).isEqualTo(Operation.CAPTURE);
        assertThat(decision.deferredDelayMs()).isEqualTo(7000);
        assertThat(decision.source()).isEqualTo(DecisionSource.OVERRIDE);
    }

    @Test
    void captureForceDeclineOverrideDoesNotApplyToCaptureFallsToCard() {
        TestCardProfile card = card(DecisionOutcome.APPROVE, null, null, 0, CaptureBehaviour.SUCCEED, RefundBehaviour.SUCCEED, null);
        OverrideSnapshot authorizeOnlyOverride = new OverrideSnapshot(OverrideScenario.FORCE_DECLINE, "card_declined", null, null);

        EngineDecision decision = engine.decide(Operation.CAPTURE, Optional.of(card), Optional.of(authorizeOnlyOverride));

        assertThat(decision.outcome()).isEqualTo(DecisionOutcome.APPROVE);
        assertThat(decision.source()).isEqualTo(DecisionSource.TEST_CARD);
    }

    @Test
    void captureNoCardFallsToModeDefault() {
        EngineDecision decision = engine.decide(Operation.CAPTURE, Optional.empty(), Optional.empty());

        assertThat(decision.outcome()).isEqualTo(DecisionOutcome.APPROVE);
        assertThat(decision.source()).isEqualTo(DecisionSource.MODE_DEFAULT);
    }

    // ── REFUND ───────────────────────────────────────────────────────────

    @Test
    void refundSucceedCardApproves() {
        TestCardProfile card = card(DecisionOutcome.APPROVE, null, null, 0, CaptureBehaviour.SUCCEED, RefundBehaviour.SUCCEED, null);
        EngineDecision decision = engine.decide(Operation.REFUND, Optional.of(card), Optional.empty());

        assertThat(decision.outcome()).isEqualTo(DecisionOutcome.APPROVE);
        assertThat(decision.source()).isEqualTo(DecisionSource.TEST_CARD);
    }

    @Test
    void refundFailCardErrors() {
        TestCardProfile card = card(DecisionOutcome.APPROVE, null, null, 0, CaptureBehaviour.SUCCEED, RefundBehaviour.FAIL, null);
        EngineDecision decision = engine.decide(Operation.REFUND, Optional.of(card), Optional.empty());

        assertThat(decision.outcome()).isEqualTo(DecisionOutcome.ERROR);
        assertThat(decision.errorCode()).isEqualTo("refund_failed");
    }

    @Test
    void refundNoOverrideScenarioEverAppliesEvenWhenOneIsActive() {
        TestCardProfile card = card(DecisionOutcome.APPROVE, null, null, 0, CaptureBehaviour.SUCCEED, RefundBehaviour.SUCCEED, null);
        OverrideSnapshot override = new OverrideSnapshot(OverrideScenario.FORCE_DECLINE, "card_declined", null, null);

        EngineDecision decision = engine.decide(Operation.REFUND, Optional.of(card), Optional.of(override));

        assertThat(decision.outcome()).isEqualTo(DecisionOutcome.APPROVE);
        assertThat(decision.source()).isEqualTo(DecisionSource.TEST_CARD);
    }

    @Test
    void refundNoCardFallsToModeDefault() {
        EngineDecision decision = engine.decide(Operation.REFUND, Optional.empty(), Optional.empty());

        assertThat(decision.outcome()).isEqualTo(DecisionOutcome.APPROVE);
        assertThat(decision.source()).isEqualTo(DecisionSource.MODE_DEFAULT);
    }

    // ── LIVE: structurally cannot see card/override at all ──────────────────

    @Test
    void liveAuthorizeIsAlwaysModeDefault() {
        EngineDecision decision = engine.decideLive(Operation.AUTHORIZE);

        assertThat(decision.outcome()).isEqualTo(DecisionOutcome.APPROVE);
        assertThat(decision.source()).isEqualTo(DecisionSource.MODE_DEFAULT);
    }

    @Test
    void liveCaptureIsAlwaysModeDefault() {
        assertThat(engine.decideLive(Operation.CAPTURE).source()).isEqualTo(DecisionSource.MODE_DEFAULT);
    }

    @Test
    void liveRefundIsAlwaysModeDefault() {
        assertThat(engine.decideLive(Operation.REFUND).source()).isEqualTo(DecisionSource.MODE_DEFAULT);
    }

    private static TestCardProfile card(DecisionOutcome outcome, String declineCode, String errorCode, int latencyMs,
                                        CaptureBehaviour captureBehaviour, RefundBehaviour refundBehaviour,
                                        Integer deferredDelayMs) {
        return new TestCardProfile("pm_card_test", outcome, declineCode, errorCode, latencyMs, captureBehaviour,
                refundBehaviour, deferredDelayMs);
    }
}
