package com.paymentflow.payment.domain;

import com.paymentflow.payment.exception.IllegalPaymentStateTransitionException;
import com.paymentflow.payment.exception.InvalidRefundAmountException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    private static Payment newPayment() {
        return Payment.create(UUID.randomUUID(), 10_000, "USD", "test payment");
    }

    @Test
    void newPaymentStartsCreatedWithZeroCapturedAndRefunded() {
        Payment payment = newPayment();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(payment.getCapturedAmountMinor()).isZero();
        assertThat(payment.getRefundedAmountMinor()).isZero();
    }

    @Test
    void authorizeThenCaptureSetsFullCapturedAmount() {
        Payment payment = newPayment();

        payment.authorize();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);

        payment.capture();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(payment.getCapturedAmountMinor()).isEqualTo(10_000);
    }

    @Test
    void capturingBeforeAuthorizationIsRejected() {
        Payment payment = newPayment();

        assertThatThrownBy(payment::capture).isInstanceOf(IllegalPaymentStateTransitionException.class);
    }

    @Test
    void authorizingTwiceIsRejected() {
        Payment payment = newPayment();
        payment.authorize();

        assertThatThrownBy(payment::authorize).isInstanceOf(IllegalPaymentStateTransitionException.class);
    }

    @Test
    void voidingAfterCaptureIsRejected() {
        Payment payment = newPayment();
        payment.authorize();
        payment.capture();

        assertThatThrownBy(payment::voidPayment).isInstanceOf(IllegalPaymentStateTransitionException.class);
    }

    @Test
    void voidingAnUnauthorizedPaymentSucceeds() {
        Payment payment = newPayment();

        payment.voidPayment();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.VOIDED);
        assertThat(payment.getStatus().isTerminal()).isTrue();
    }

    @Test
    void failingCapturesAReason() {
        Payment payment = newPayment();

        payment.fail("card declined");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("card declined");
    }

    @Test
    void fullRefundAfterCaptureLandsOnRefunded() {
        Payment payment = newPayment();
        payment.authorize();
        payment.capture();

        payment.refund(10_000);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getRefundedAmountMinor()).isEqualTo(10_000);
    }

    @Test
    void partialRefundLandsOnPartiallyRefunded() {
        Payment payment = newPayment();
        payment.authorize();
        payment.capture();

        payment.refund(4_000);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(payment.getRefundedAmountMinor()).isEqualTo(4_000);
    }

    @Test
    void secondPartialRefundCompletingTheAmountLandsOnRefunded() {
        Payment payment = newPayment();
        payment.authorize();
        payment.capture();
        payment.refund(4_000);

        payment.refund(6_000);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getRefundedAmountMinor()).isEqualTo(10_000);
    }

    @Test
    void refundingMoreThanRemainingIsRejected() {
        Payment payment = newPayment();
        payment.authorize();
        payment.capture();
        payment.refund(4_000);

        assertThatThrownBy(() -> payment.refund(7_000)).isInstanceOf(InvalidRefundAmountException.class);
        // Rejected refund must not have mutated state.
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(payment.getRefundedAmountMinor()).isEqualTo(4_000);
    }

    @Test
    void refundingZeroOrNegativeIsRejected() {
        Payment payment = newPayment();
        payment.authorize();
        payment.capture();

        assertThatThrownBy(() -> payment.refund(0)).isInstanceOf(InvalidRefundAmountException.class);
        assertThatThrownBy(() -> payment.refund(-1)).isInstanceOf(InvalidRefundAmountException.class);
    }

    @Test
    void refundingBeforeCaptureIsRejected() {
        Payment payment = newPayment();

        assertThatThrownBy(() -> payment.refund(1_000)).isInstanceOf(IllegalPaymentStateTransitionException.class);
    }
}
