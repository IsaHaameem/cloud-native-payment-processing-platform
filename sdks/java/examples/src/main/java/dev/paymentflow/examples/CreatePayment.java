package dev.paymentflow.examples;

import dev.paymentflow.PaymentFlow;
import dev.paymentflow.model.PaymentResponse;
import dev.paymentflow.resources.Payments;

/**
 * The first payment. Compiled, never run — it imports the SDK exactly as an integrator does, so
 * an API change that broke it fails {@code check} rather than being found by someone copying it.
 */
public final class CreatePayment {

    private CreatePayment() {}

    public static void main(String[] args) {
        // The key alone decides whose data you see and which mode. Keep it server-side.
        PaymentFlow client = PaymentFlow.builder()
                .apiKey(System.getenv("PAYMENTFLOW_API_KEY"))   // sk_test_…
                .baseUrl("https://api.paymentflow.dev")
                .build();

        // Amounts are integers in the currency's minor unit. 1000 = 10.00.
        PaymentResponse payment = client.payments().create(
                Payments.params()
                        .amountMinor(1000)
                        .currency("USD")
                        .description("Order A-1234")
                        .paymentMethodToken("pm_card_visa"));   // a test card that approves

        System.out.println(payment.id() + " " + payment.status());   // -> "pay_…", "created"
    }
}
