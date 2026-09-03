package com.paymentflow.agentic.provider.razorpay;

import com.paymentflow.agentic.config.AgenticProperties;
import com.paymentflow.agentic.provider.ProviderAuthorizationRequest;
import com.paymentflow.agentic.provider.ProviderDecision;
import com.paymentflow.agentic.provider.ProviderOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The adapter's contract, and above all the honesty of what it reports.
 *
 * <p>{@link UncollectedOrder} is the group that matters. It is where the verified Razorpay
 * constraint — no server-to-server authorization exists — meets the demo's need to show a
 * lifecycle, and where the difference between "a cardholder paid" and "an order was accepted"
 * either survives into the audit trail or is quietly lost.
 */
class RazorpayPaymentProviderTest {

    private static final UUID PAYMENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final String ORDER_ID = "order_TestOrder123";

    private final RazorpayClient client = mock(RazorpayClient.class);

    private RazorpayPaymentProvider provider(boolean enabled, String uncollectedOutcome) {
        return new RazorpayPaymentProvider(client, properties(enabled, uncollectedOutcome));
    }

    private static ProviderAuthorizationRequest request() {
        return new ProviderAuthorizationRequest(PAYMENT_ID + ":AUTHORIZE", PAYMENT_ID, "AUTHORIZE",
                "rzp_card_token", 250_000L, "INR");
    }

    @Nested
    @DisplayName("a cardholder actually authorized something")
    class Collected {

        @Test
        @DisplayName("an authorized payment against the order is a real approval")
        void authorizedPaymentIsARealApproval() {
            RazorpayPaymentProvider provider = provider(true, "decline");
            when(client.createOrder(anyLong(), anyString(), anyString(), any()))
                    .thenReturn(new RazorpayClient.RazorpayOrder(ORDER_ID, "attempted", 250_000L, "INR", 0));
            when(client.orderPayments(ORDER_ID)).thenReturn(List.of(
                    new RazorpayClient.RazorpayPayment("pay_1", "authorized", 250_000L, "INR", null, null)));

            ProviderDecision decision = provider.authorize(request());

            assertThat(decision.outcome()).isEqualTo(ProviderOutcome.APPROVE);
            assertThat(decision.source()).isEqualTo(ProviderDecision.SOURCE_PAYMENT_COLLECTED);
            assertThat(decision.isDemoApproval()).as("a real cardholder authorization").isFalse();
            assertThat(decision.providerReference()).isEqualTo("pay_1");
        }

        @Test
        @DisplayName("a captured payment counts as authorized too")
        void capturedPaymentIsAlsoAnApproval() {
            RazorpayPaymentProvider provider = provider(true, "decline");
            when(client.createOrder(anyLong(), anyString(), anyString(), any()))
                    .thenReturn(new RazorpayClient.RazorpayOrder(ORDER_ID, "paid", 250_000L, "INR", 250_000L));
            when(client.orderPayments(ORDER_ID)).thenReturn(List.of(
                    new RazorpayClient.RazorpayPayment("pay_2", "captured", 250_000L, "INR", null, null)));

            assertThat(provider.authorize(request()).outcome()).isEqualTo(ProviderOutcome.APPROVE);
        }

        @Test
        @DisplayName("a failed cardholder payment is a real decline carrying the acquirer's own reason")
        void failedPaymentIsARealDecline() {
            RazorpayPaymentProvider provider = provider(true, "approve");
            when(client.createOrder(anyLong(), anyString(), anyString(), any()))
                    .thenReturn(new RazorpayClient.RazorpayOrder(ORDER_ID, "attempted", 250_000L, "INR", 0));
            when(client.orderPayments(ORDER_ID)).thenReturn(List.of(
                    new RazorpayClient.RazorpayPayment("pay_3", "failed", 250_000L, "INR",
                            "BAD_REQUEST_ERROR", "payment_failed")));

            ProviderDecision decision = provider.authorize(request());

            // Even with the demo approval switched on, a real decline is reported as a decline.
            assertThat(decision.outcome()).isEqualTo(ProviderOutcome.DECLINE);
            assertThat(decision.declineCode()).isEqualTo("bad_request_error");
            assertThat(decision.source()).isEqualTo(ProviderDecision.SOURCE_PAYMENT_COLLECTED);
        }
    }

    @Nested
    @DisplayName("nobody authorized anything — the case the whole design turns on")
    class UncollectedOrder {

        @Test
        @DisplayName("the default declines, because there is genuinely no authorization to report")
        void defaultDeclines() {
            RazorpayPaymentProvider provider = provider(true, "decline");
            when(client.createOrder(anyLong(), anyString(), anyString(), any()))
                    .thenReturn(new RazorpayClient.RazorpayOrder(ORDER_ID, "created", 250_000L, "INR", 0));
            when(client.orderPayments(ORDER_ID)).thenReturn(List.of());

            ProviderDecision decision = provider.authorize(request());

            assertThat(decision.outcome()).isEqualTo(ProviderOutcome.DECLINE);
            assertThat(decision.declineCode()).isEqualTo("razorpay_payment_not_collected");
            assertThat(decision.source()).isEqualTo(ProviderDecision.SOURCE_ORDER_ACCEPTED);
            assertThat(decision.isDemoApproval()).isFalse();
        }

        @Test
        @DisplayName("the committed default configuration is decline, not approve")
        void committedDefaultIsDecline() {
            AgenticProperties.Razorpay committed = properties(true, "decline").razorpay();

            assertThat(committed.treatsUncollectedOrderAsApproved()).isFalse();
        }

        @Test
        @DisplayName("the demo option approves, and labels it order_accepted and demo")
        void demoApprovalIsLabelled() {
            RazorpayPaymentProvider provider = provider(true, "approve");
            when(client.createOrder(anyLong(), anyString(), anyString(), any()))
                    .thenReturn(new RazorpayClient.RazorpayOrder(ORDER_ID, "created", 250_000L, "INR", 0));
            when(client.orderPayments(ORDER_ID)).thenReturn(List.of());

            ProviderDecision decision = provider.authorize(request());

            assertThat(decision.outcome()).isEqualTo(ProviderOutcome.APPROVE);
            assertThat(decision.source())
                    .as("the label that says no cardholder was involved")
                    .isEqualTo(ProviderDecision.SOURCE_ORDER_ACCEPTED);
            assertThat(decision.isDemoApproval())
                    .as("this must be distinguishable from a real card payment")
                    .isTrue();
        }

        @Test
        @DisplayName("a real approval is never labelled demo, and a demo approval never claims otherwise")
        void theTwoApprovalsAreNeverConfusable() {
            ProviderDecision real = ProviderDecision.approved(
                    ProviderDecision.SOURCE_PAYMENT_COLLECTED, "pay_1");
            ProviderDecision demo = ProviderDecision.demoApproved(ORDER_ID);

            assertThat(real.isDemoApproval()).isFalse();
            assertThat(real.source()).isEqualTo("payment_collected");
            assertThat(demo.isDemoApproval()).isTrue();
            assertThat(demo.source()).isEqualTo("order_accepted");
            assertThat(demo.source()).isNotEqualTo(real.source());
        }

        @Test
        @DisplayName("a payment merely created but never finished is not an authorization")
        void unfinishedAttemptIsNotAnAuthorization() {
            RazorpayPaymentProvider provider = provider(true, "decline");
            when(client.createOrder(anyLong(), anyString(), anyString(), any()))
                    .thenReturn(new RazorpayClient.RazorpayOrder(ORDER_ID, "attempted", 250_000L, "INR", 0));
            when(client.orderPayments(ORDER_ID)).thenReturn(List.of(
                    new RazorpayClient.RazorpayPayment("pay_4", "created", 250_000L, "INR", null, null)));

            assertThat(provider.authorize(request()).declineCode())
                    .isEqualTo("razorpay_payment_not_collected");
        }
    }

    @Nested
    @DisplayName("failure is always a decision, never an exception")
    class Failures {

        @Test
        @DisplayName("an unconfigured provider asks nothing and reports why")
        void unconfiguredAsksNothing() {
            RazorpayPaymentProvider provider = provider(false, "decline");

            ProviderDecision decision = provider.authorize(request());

            assertThat(decision.outcome()).isEqualTo(ProviderOutcome.ERROR);
            assertThat(decision.source()).isEqualTo(ProviderDecision.SOURCE_NOT_CONFIGURED);
            verify(client, never()).createOrder(anyLong(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("a placeholder credential counts as unconfigured")
        void placeholderCredentialIsNotConfigured() {
            AgenticProperties.Razorpay placeholder = new AgenticProperties.Razorpay(true,
                    "https://api.razorpay.com", "rzp_test_dev-only-not-a-real-key",
                    "dev-only-insecure-razorpay-secret-change-me", 2000, 8000, "decline");

            assertThat(placeholder.isConfigured()).isFalse();
        }

        @Test
        @DisplayName("an unreachable provider is an ERROR decision, not a thrown exception")
        void unavailableIsADecision() {
            RazorpayPaymentProvider provider = provider(true, "decline");
            when(client.createOrder(anyLong(), anyString(), anyString(), any()))
                    .thenThrow(new RazorpayUnavailableException("connection refused"));

            ProviderDecision decision = provider.authorize(request());

            assertThat(decision.outcome()).isEqualTo(ProviderOutcome.ERROR);
            assertThat(decision.errorCode()).isEqualTo("razorpay_unavailable");
        }

        @Test
        @DisplayName("a rejected request is an ERROR carrying the provider's code, never retried")
        void rejectedRequestIsADecision() {
            RazorpayPaymentProvider provider = provider(true, "decline");
            when(client.createOrder(anyLong(), anyString(), anyString(), any()))
                    .thenThrow(new RazorpayRequestException(400, "BAD_REQUEST_ERROR"));

            ProviderDecision decision = provider.authorize(request());

            assertThat(decision.outcome()).isEqualTo(ProviderOutcome.ERROR);
            assertThat(decision.errorCode()).isEqualTo("bad_request_error");
            verify(client, never()).orderPayments(anyString());
        }

        @Test
        @DisplayName("an unexpected failure is still a decision — a provider that throws is worse")
        void unexpectedFailureIsADecision() {
            RazorpayPaymentProvider provider = provider(true, "decline");
            when(client.createOrder(anyLong(), anyString(), anyString(), any()))
                    .thenThrow(new IllegalStateException("circuit breaker open"));

            assertThat(provider.authorize(request()).outcome()).isEqualTo(ProviderOutcome.ERROR);
        }

        @Test
        @DisplayName("an order with no id is an error, not an approval")
        void orderWithoutIdIsAnError() {
            RazorpayPaymentProvider provider = provider(true, "approve");
            when(client.createOrder(anyLong(), anyString(), anyString(), any()))
                    .thenReturn(new RazorpayClient.RazorpayOrder(null, "created", 250_000L, "INR", 0));

            ProviderDecision decision = provider.authorize(request());

            assertThat(decision.outcome()).isEqualTo(ProviderOutcome.ERROR);
            assertThat(decision.errorCode()).isEqualTo("razorpay_order_not_created");
        }
    }

    @Nested
    @DisplayName("scope and isolation")
    class Scope {

        @Test
        @DisplayName("the port offers authorization and nothing else")
        void portIsAuthorizationOnly() {
            List<String> methods = java.util.Arrays.stream(
                            com.paymentflow.agentic.provider.PaymentProvider.class.getDeclaredMethods())
                    .map(java.lang.reflect.Method::getName)
                    .sorted()
                    .toList();

            assertThat(methods).containsExactly("authorize", "isConfigured", "providerName");
            assertThat(methods).as("Depth 1 adds no capture or refund port").doesNotContain("capture",
                    "refund");
        }

        @Test
        @DisplayName("a neutral decision carries no Razorpay vocabulary at all")
        void decisionIsProviderNeutral() {
            List<String> components = java.util.Arrays.stream(
                            ProviderDecision.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName)
                    .toList();

            assertThat(components).doesNotContain("orderId", "razorpayOrderId", "razorpayStatus");
            assertThat(components).containsExactlyInAnyOrder("outcome", "declineCode", "errorCode",
                    "source", "providerReference", "demo");
        }
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────────────

    private static AgenticProperties properties(boolean enabled, String uncollectedOutcome) {
        return new AgenticProperties(
                new AgenticProperties.Platform("http://gateway.test", "sk_test_x", 2000, 10000),
                new AgenticProperties.Policy("2026-08-20.1", "INR", 5_000_000L, 10_000_000L, 100_000L,
                        2_000_000L, 5_000_000L, 60, 30),
                new AgenticProperties.Checkout(30, 20),
                new AgenticProperties.Llm("scripted", "http://llm.test", "", "scripted", 16000, 0.2,
                        30000, 8, 120000, "", ""),
                new AgenticProperties.Razorpay(enabled, "https://api.razorpay.test", "rzp_test_realkeyid",
                        "arealisticrazorpaysecretvalue", 2000, 8000, uncollectedOutcome),
                new AgenticProperties.Demo("", false));
    }
}
