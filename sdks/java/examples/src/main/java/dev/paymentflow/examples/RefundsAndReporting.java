package dev.paymentflow.examples;

import dev.paymentflow.PaymentFlow;
import dev.paymentflow.model.AnalyticsSummaryResponse;
import dev.paymentflow.model.PaymentResponse;
import dev.paymentflow.model.RefundResponse;
import dev.paymentflow.resources.Payments;

/** A partial refund, then a read of the reporting surface. */
public final class RefundsAndReporting {

    private RefundsAndReporting() {}

    public static void main(String[] args) {
        PaymentFlow client = PaymentFlow.fromEnvironment();

        // refund() returns the payment; the refund is the newest entry in its refunds array.
        PaymentResponse payment = client.payments().refund("pay_123",
                Payments.refundParams().amountMinor(500).reason("customer changed their mind"), null);
        RefundResponse newest = payment.refunds().get(payment.refunds().size() - 1);
        System.out.println("refunded " + newest.amountMinor() + " " + newest.currency());

        AnalyticsSummaryResponse summary = client.analytics()
                .retrievePaymentSummary("2026-08-01T00:00:00Z", "2026-08-31T23:59:59Z", null);
        System.out.println("captured in window: " + summary.totalCapturedAmountMinor());
        System.out.println("success rate: " + summary.successRate()); // null when nothing was attempted
    }
}
