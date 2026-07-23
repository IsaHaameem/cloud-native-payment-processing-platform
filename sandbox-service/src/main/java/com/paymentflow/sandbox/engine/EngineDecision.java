package com.paymentflow.sandbox.engine;

import com.paymentflow.sandbox.domain.DecisionOutcome;
import com.paymentflow.sandbox.domain.DecisionSource;
import com.paymentflow.sandbox.domain.Operation;

/**
 * What {@link DecisionEngine} produces for one operation. {@code deferredOperation}/
 * {@code deferredDelayMs} are set only when this decision implies a later asynchronous
 * settlement (a card's {@code CaptureBehaviour.DEFER}, or an override's
 * {@code DELAY_SETTLEMENT}) — M17.6 is what actually schedules and delivers it.
 */
public record EngineDecision(
        DecisionOutcome outcome,
        String declineCode,
        String errorCode,
        int latencyMs,
        DecisionSource source,
        Operation deferredOperation,
        Integer deferredDelayMs) {

    static EngineDecision approve(DecisionSource source, int latencyMs) {
        return new EngineDecision(DecisionOutcome.APPROVE, null, null, latencyMs, source, null, null);
    }

    static EngineDecision decline(String declineCode, DecisionSource source, int latencyMs) {
        return new EngineDecision(DecisionOutcome.DECLINE, declineCode, null, latencyMs, source, null, null);
    }

    static EngineDecision error(String errorCode, DecisionSource source, int latencyMs) {
        return new EngineDecision(DecisionOutcome.ERROR, null, errorCode, latencyMs, source, null, null);
    }

    static EngineDecision requireAction(DecisionSource source, int latencyMs) {
        return new EngineDecision(DecisionOutcome.REQUIRE_ACTION, null, null, latencyMs, source, null, null);
    }

    static EngineDecision deferred(Operation deferredOperation, int deferredDelayMs, DecisionSource source) {
        return new EngineDecision(DecisionOutcome.APPROVE, null, null, 0, source, deferredOperation, deferredDelayMs);
    }
}
