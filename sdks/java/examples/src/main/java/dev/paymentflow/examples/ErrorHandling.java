package dev.paymentflow.examples;

import dev.paymentflow.ApiConnectionException;
import dev.paymentflow.InvalidRequestException;
import dev.paymentflow.PaymentFlow;
import dev.paymentflow.PaymentFlowException;
import dev.paymentflow.RateLimitException;
import dev.paymentflow.resources.Payments;

/** Branch on the exception class, not the status code. */
public final class ErrorHandling {

    private ErrorHandling() {}

    public static void main(String[] args) {
        PaymentFlow client = PaymentFlow.fromEnvironment();
        try {
            client.payments().create(Payments.params().amountMinor(1000).currency("US"));
        } catch (InvalidRequestException e) {
            // The request will be rejected identically however many times it is sent.
            System.err.println("fix the request: " + e.param() + " — " + e.getMessage());
        } catch (RateLimitException e) {
            // The retry loop already waited out a short one; this is the budget exhausted.
            System.err.println("retry after " + e.retryAfterSeconds() + "s");
        } catch (ApiConnectionException e) {
            // No response — "did it happen?" is genuinely unknown. The Idempotency-Key is why a
            // retry is safe.
            System.err.println("network: " + e.getMessage());
        } catch (PaymentFlowException e) {
            // Catching this and nothing else is already a complete, correct handler.
            System.err.println("request " + e.requestId() + " failed: " + e.getMessage());
        }
    }
}
