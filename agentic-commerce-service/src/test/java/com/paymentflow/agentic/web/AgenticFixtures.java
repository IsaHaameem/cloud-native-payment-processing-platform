package com.paymentflow.agentic.web;

import com.paymentflow.agentic.config.AgenticProperties;

/**
 * Shared {@link AgenticProperties} for the web-layer tests, so the policy numbers a test asserts
 * on are the committed defaults rather than an ad-hoc set per test file.
 */
final class AgenticFixtures {

    private AgenticFixtures() {
    }

    static AgenticProperties properties(String demoMerchantId) {
        return new AgenticProperties(
                new AgenticProperties.Platform("http://gateway.test", "sk_test_x", 2000, 10000),
                new AgenticProperties.Policy("2026-08-20.1", "INR", 5_000_000L, 10_000_000L, 100_000L,
                        2_000_000L, 5_000_000L, 60, 30),
                new AgenticProperties.Checkout(30, 20),
                new AgenticProperties.Llm("anthropic", "http://llm.test", "", "claude-opus-5", 16000, 0.2,
                        30000, 8, 120000, "", ""),
                new AgenticProperties.Razorpay(false, "https://example.invalid", "", "", 2000, 8000,
                        "decline"),
                new AgenticProperties.Demo(demoMerchantId, false));
    }
}
