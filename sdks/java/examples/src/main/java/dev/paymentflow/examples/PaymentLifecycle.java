package dev.paymentflow.examples;

import dev.paymentflow.PaymentFlow;
import dev.paymentflow.model.PaymentResponse;
import dev.paymentflow.resources.Payments;

/** Create → authorize → capture → verify, with the declined-card branch surfaced. */
public final class PaymentLifecycle {

    private PaymentLifecycle() {}

    public static void main(String[] args) {
        PaymentFlow client = PaymentFlow.fromEnvironment();

        PaymentResponse payment = client.payments().create(
                Payments.params().amountMinor(1000).currency("USD").paymentMethodToken("pm_card_visa"));

        PaymentResponse authorized = client.payments().authorize(payment.id());
        if ("failed".equals(authorized.status())) {
            // The acquirer's own reason — show it, let the customer retry.
            throw new IllegalStateException(authorized.failureReason());
        }

        PaymentResponse captured = client.payments().capture(payment.id());
        System.out.println(captured.status() + " " + captured.capturedAmountMinor()); // -> "captured", 1000

        PaymentResponse check = client.payments().retrieve(payment.id());
        System.out.println(check.status()); // -> "captured"
    }
}
