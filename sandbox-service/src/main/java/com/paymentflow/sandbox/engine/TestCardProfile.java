package com.paymentflow.sandbox.engine;

import com.paymentflow.sandbox.domain.CaptureBehaviour;
import com.paymentflow.sandbox.domain.DecisionOutcome;
import com.paymentflow.sandbox.domain.RefundBehaviour;
import com.paymentflow.sandbox.domain.TestCard;

/**
 * The fields {@link com.paymentflow.sandbox.engine.DecisionEngine} needs from a test
 * card, as a plain record rather than the JPA entity directly — {@link TestCard} has no
 * public constructor (JPA-only, by design, matching every other entity in this
 * platform), so the engine takes this instead, keeping it constructable in a unit test
 * with no persistence context at all.
 */
public record TestCardProfile(
        String token,
        DecisionOutcome outcome,
        String declineCode,
        String errorCode,
        int latencyMs,
        CaptureBehaviour captureBehaviour,
        RefundBehaviour refundBehaviour,
        Integer deferredDelayMs) {

    public static TestCardProfile of(TestCard card) {
        return new TestCardProfile(card.getToken(), card.getOutcome(), card.getDeclineCode(), card.getErrorCode(),
                card.getLatencyMs(), card.getCaptureBehaviour(), card.getRefundBehaviour(), card.getDeferredDelayMs());
    }
}
