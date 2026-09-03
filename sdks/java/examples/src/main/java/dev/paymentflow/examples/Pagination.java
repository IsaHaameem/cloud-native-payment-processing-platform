package dev.paymentflow.examples;

import dev.paymentflow.PaymentFlow;
import dev.paymentflow.model.PaymentResponse;
import dev.paymentflow.resources.Payments;

/** Iterating a list is already the paginating thing — a for loop crosses page boundaries. */
public final class Pagination {

    private Pagination() {}

    public static void main(String[] args) {
        PaymentFlow client = PaymentFlow.fromEnvironment();

        long captured = 0;
        for (PaymentResponse payment : client.payments().list(Payments.listParams().status("captured"), null)) {
            captured++;
            if (captured >= 1000) {
                break; // a break stops making requests here, not after the last page
            }
        }
        System.out.println("captured payments seen: " + captured);
    }
}
