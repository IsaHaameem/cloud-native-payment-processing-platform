package com.paymentflow.agentic.policy;

import com.paymentflow.agentic.checkout.CheckoutStatus;
import com.paymentflow.agentic.config.AgenticProperties;

import java.util.UUID;

/**
 * The thresholds and request shapes the policy tests share.
 *
 * <p>The numbers here are the committed defaults from {@code application.yaml}, copied
 * deliberately rather than loaded. A policy test asserting against whatever the configuration
 * happens to say would pass equally well if someone raised a cap by a factor of a hundred;
 * pinning the values means a change to a financial threshold has to be made in two places, and
 * the second one is a test whose name says what the number is for.
 */
final class PolicyFixtures {

    static final String POLICY_VERSION = "2026-08-20.1";
    static final String CURRENCY = "INR";

    static final long MAX_PAYMENT = 5_000_000L;          // ₹50,000
    static final long MAX_CONVERSATION_SPEND = 10_000_000L;   // ₹1,00,000
    static final long REFUND_APPROVAL_THRESHOLD = 100_000L;   // ₹1,000
    static final long MAX_REFUND = 2_000_000L;           // ₹20,000
    static final long MAX_CONVERSATION_REFUND = 5_000_000L;   // ₹50,000
    static final int MAX_TOOL_CALLS = 60;
    static final int APPROVAL_TTL_MINUTES = 30;

    static final UUID MERCHANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID CONVERSATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID CHECKOUT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final UUID PAYMENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private PolicyFixtures() {
    }

    static PolicyEngine engine() {
        return new PolicyEngine(properties(policy()));
    }

    static AgenticProperties.Policy policy() {
        return new AgenticProperties.Policy(POLICY_VERSION, CURRENCY, MAX_PAYMENT, MAX_CONVERSATION_SPEND,
                REFUND_APPROVAL_THRESHOLD, MAX_REFUND, MAX_CONVERSATION_REFUND, MAX_TOOL_CALLS,
                APPROVAL_TTL_MINUTES);
    }

    /** Only the policy group matters to the engine; the rest is present because the record requires it. */
    static AgenticProperties properties(AgenticProperties.Policy policy) {
        return new AgenticProperties(
                new AgenticProperties.Platform("http://localhost:8080", "sk_test_fixture", 2000, 10000),
                policy,
                new AgenticProperties.Checkout(30, 20),
                new AgenticProperties.Llm("anthropic", "https://example.invalid", "", "model", 2048, 0.2, 30000, 8, 120000, "", ""),
                new AgenticProperties.Razorpay(false, "https://example.invalid", "", "", 2000, 8000, "decline"),
                new AgenticProperties.Demo("", false));
    }

    static PolicyRequest.Actor actor() {
        return new PolicyRequest.Actor(MERCHANT_ID, "test", "session-1", "merchant:11111111/session-1");
    }

    static PolicyRequest.Conversation conversation() {
        return new PolicyRequest.Conversation(CONVERSATION_ID, true, 0, 0, 0);
    }

    static PolicyRequest read() {
        return new PolicyRequest(actor(), conversation(), "search_products", PolicyOperation.CATALOG_READ,
                PolicyRequest.Target.none());
    }

    /** A payment for {@code amountMinor} against an open checkout — the ordinary permitted case. */
    static PolicyRequest payment(long amountMinor) {
        return payment(conversation(), amountMinor, CheckoutStatus.OPEN, CURRENCY);
    }

    static PolicyRequest payment(PolicyRequest.Conversation conversation, long amountMinor,
                                 CheckoutStatus status, String currency) {
        return new PolicyRequest(actor(), conversation, "complete_checkout", PolicyOperation.CHECKOUT_PAY,
                new PolicyRequest.Target(CHECKOUT_ID, status, null, amountMinor, currency));
    }

    static PolicyRequest refund(long amountMinor) {
        return refund(conversation(), amountMinor, CURRENCY);
    }

    static PolicyRequest refund(PolicyRequest.Conversation conversation, long amountMinor, String currency) {
        return new PolicyRequest(actor(), conversation, "request_refund", PolicyOperation.REFUND_CREATE,
                new PolicyRequest.Target(null, null, PAYMENT_ID, amountMinor, currency));
    }
}
