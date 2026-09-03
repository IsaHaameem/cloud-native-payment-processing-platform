package com.paymentflow.agentic.policy;

import com.paymentflow.agentic.config.AgenticProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PolicyCatalog} is the projection the portal's Policies screen reads. The one guarantee
 * that matters: <b>a number shown there must be the number the engine enforces</b>. These tests
 * pin that — the catalogue rows are derived from {@link PolicyRule} and {@link AgenticProperties},
 * never hand-copied, so a config change or a new rule flows through without an edit here.
 */
class PolicyCatalogTest {

    private final AgenticProperties.Policy policy = new AgenticProperties.Policy(
            "2026-08-20.1", "INR",
            5_000_000L,   // max-payment-amount-minor
            10_000_000L,  // max-conversation-spend-minor
            100_000L,     // refund-approval-threshold-minor
            2_000_000L,   // max-refund-amount-minor
            5_000_000L,   // max-conversation-refund-minor
            60, 30);

    private final PolicyCatalog catalog = new PolicyCatalog(propertiesWith(policy));

    @Test
    @DisplayName("every PolicyRule appears exactly once, and nothing else does")
    void oneRowPerRule() {
        List<PolicyCatalog.PolicyRuleView> rows = catalog.describe();
        assertThat(rows).hasSize(PolicyRule.values().length);
        assertThat(rows.stream().map(PolicyCatalog.PolicyRuleView::id).distinct().count())
                .isEqualTo(PolicyRule.values().length);
        assertThat(rows.stream().map(PolicyCatalog.PolicyRuleView::id))
                .containsExactlyInAnyOrderElementsOf(
                        java.util.Arrays.stream(PolicyRule.values()).map(PolicyRule::id).toList());
    }

    @Test
    @DisplayName("each row's decision and reason code are the rule's own, verbatim")
    void decisionsMatchTheRule() {
        Map<String, PolicyRule> byId = java.util.Arrays.stream(PolicyRule.values())
                .collect(Collectors.toMap(PolicyRule::id, Function.identity()));
        for (PolicyCatalog.PolicyRuleView row : catalog.describe()) {
            PolicyRule rule = byId.get(row.id());
            assertThat(row.decision()).isEqualTo(rule.decision());
            assertThat(row.reasonCode()).isEqualTo(rule.reasonCode());
        }
    }

    @Test
    @DisplayName("the money thresholds are the configured values, to the unit")
    void thresholdsAreTheConfiguredNumbers() {
        Map<String, PolicyCatalog.PolicyRuleView> byId = catalog.describe().stream()
                .collect(Collectors.toMap(PolicyCatalog.PolicyRuleView::id, Function.identity()));

        assertThat(byId.get("payment-amount-cap").threshold()).isEqualTo(policy.maxPaymentAmountMinor());
        assertThat(byId.get("conversation-spend-budget").threshold())
                .isEqualTo(policy.maxConversationSpendMinor());
        assertThat(byId.get("refund-amount-cap").threshold()).isEqualTo(policy.maxRefundAmountMinor());
        assertThat(byId.get("conversation-refund-budget").threshold())
                .isEqualTo(policy.maxConversationRefundMinor());
        assertThat(byId.get("refund-approval-threshold").threshold())
                .isEqualTo(policy.refundApprovalThresholdMinor());
        assertThat(byId.get("tool-call-ceiling").threshold())
                .isEqualTo((long) policy.maxToolCallsPerConversation());
    }

    @Test
    @DisplayName("only the refund approval threshold is waivable by a human")
    void onlyApprovalThresholdIsWaivable() {
        List<PolicyCatalog.PolicyRuleView> waivable = catalog.describe().stream()
                .filter(PolicyCatalog.PolicyRuleView::waivable)
                .toList();
        assertThat(waivable).singleElement()
                .satisfies(row -> assertThat(row.id()).isEqualTo("refund-approval-threshold"));
    }

    @Test
    @DisplayName("a non-positive cap is reported as disabling the operation, not unbounding it")
    void nonPositiveCapIsDisabled() {
        AgenticProperties.Policy blanked = new AgenticProperties.Policy(
                "v", "INR", 0L, 10_000_000L, 100_000L, 2_000_000L, 5_000_000L, 60, 30);
        PolicyCatalog c = new PolicyCatalog(propertiesWith(blanked));
        PolicyCatalog.PolicyRuleView cap = c.describe().stream()
                .filter(r -> r.id().equals("payment-amount-cap")).findFirst().orElseThrow();
        assertThat(cap.disabled()).isTrue();
    }

    @Test
    @DisplayName("version and currency pass through from configuration")
    void versionAndCurrency() {
        assertThat(catalog.policyVersion()).isEqualTo("2026-08-20.1");
        assertThat(catalog.currency()).isEqualTo("INR");
    }

    private static AgenticProperties propertiesWith(AgenticProperties.Policy policy) {
        return new AgenticProperties(
                new AgenticProperties.Platform("http://gateway.test", "", 2000, 10000),
                policy,
                new AgenticProperties.Checkout(30, 20),
                new AgenticProperties.Llm("anthropic", "", "", "m", 16000, 0.2, 30000, 8, 120000, "", ""),
                new AgenticProperties.Razorpay(false, "", "", "", 2000, 8000, "decline"),
                new AgenticProperties.Demo("", false));
    }
}
