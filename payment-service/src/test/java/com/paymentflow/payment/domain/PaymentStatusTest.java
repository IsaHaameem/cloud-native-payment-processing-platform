package com.paymentflow.payment.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentStatusTest {

    @Test
    void createdCanMoveToAuthorizedVoidedOrFailed() {
        assertThat(PaymentStatus.CREATED.canTransitionTo(PaymentStatus.AUTHORIZED)).isTrue();
        assertThat(PaymentStatus.CREATED.canTransitionTo(PaymentStatus.VOIDED)).isTrue();
        assertThat(PaymentStatus.CREATED.canTransitionTo(PaymentStatus.FAILED)).isTrue();
        assertThat(PaymentStatus.CREATED.canTransitionTo(PaymentStatus.CAPTURED)).isFalse();
        assertThat(PaymentStatus.CREATED.canTransitionTo(PaymentStatus.REFUNDED)).isFalse();
    }

    @Test
    void authorizedCanMoveToCapturedVoidedOrFailed() {
        assertThat(PaymentStatus.AUTHORIZED.canTransitionTo(PaymentStatus.CAPTURED)).isTrue();
        assertThat(PaymentStatus.AUTHORIZED.canTransitionTo(PaymentStatus.VOIDED)).isTrue();
        assertThat(PaymentStatus.AUTHORIZED.canTransitionTo(PaymentStatus.FAILED)).isTrue();
        assertThat(PaymentStatus.AUTHORIZED.canTransitionTo(PaymentStatus.CREATED)).isFalse();
        assertThat(PaymentStatus.AUTHORIZED.canTransitionTo(PaymentStatus.REFUNDED)).isFalse();
    }

    @Test
    void capturedCanOnlyMoveToRefundedOrPartiallyRefunded() {
        assertThat(PaymentStatus.CAPTURED.canTransitionTo(PaymentStatus.REFUNDED)).isTrue();
        assertThat(PaymentStatus.CAPTURED.canTransitionTo(PaymentStatus.PARTIALLY_REFUNDED)).isTrue();
        assertThat(PaymentStatus.CAPTURED.canTransitionTo(PaymentStatus.VOIDED)).isFalse();
        assertThat(PaymentStatus.CAPTURED.canTransitionTo(PaymentStatus.AUTHORIZED)).isFalse();
    }

    @Test
    void partiallyRefundedCanMoveToItselfOrFullyRefunded() {
        assertThat(PaymentStatus.PARTIALLY_REFUNDED.canTransitionTo(PaymentStatus.PARTIALLY_REFUNDED)).isTrue();
        assertThat(PaymentStatus.PARTIALLY_REFUNDED.canTransitionTo(PaymentStatus.REFUNDED)).isTrue();
        assertThat(PaymentStatus.PARTIALLY_REFUNDED.canTransitionTo(PaymentStatus.CAPTURED)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"REFUNDED", "FAILED", "VOIDED"})
    void terminalStatusesAcceptNoTransitions(PaymentStatus terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        for (PaymentStatus target : PaymentStatus.values()) {
            assertThat(terminal.canTransitionTo(target)).isFalse();
        }
    }

    @Test
    void nonTerminalStatusesAreNotTerminal() {
        assertThat(PaymentStatus.CREATED.isTerminal()).isFalse();
        assertThat(PaymentStatus.AUTHORIZED.isTerminal()).isFalse();
        assertThat(PaymentStatus.CAPTURED.isTerminal()).isFalse();
        assertThat(PaymentStatus.PARTIALLY_REFUNDED.isTerminal()).isFalse();
    }
}
