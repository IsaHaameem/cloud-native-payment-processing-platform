package com.paymentflow.agentic.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The metrics layer's one real risk is tag cardinality, and its one real security risk is the
 * same thing wearing a different hat.
 *
 * <p>A tool name the model invented is arbitrary model-authored text. Tagging a counter with it
 * would create a new time series per invention — and would put that text into a metrics backend,
 * which is a place nobody audits for secrets. These tests pin the collapse that prevents both.
 */
class AgentMetricsTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final AgentMetrics metrics = new AgentMetrics(registry);

    @Test
    @DisplayName("a registered tool is tagged by its own name")
    void registeredToolsAreTaggedByName() {
        for (String tool : new String[] {"search_products", "get_product", "create_checkout",
                "complete_checkout", "request_refund", "get_payment_status",
                "explain_payment_outcome"}) {
            assertThat(AgentMetrics.toolTag(tool)).isEqualTo(tool);
        }
    }

    @Test
    @DisplayName("a tool name the model invented never becomes a tag")
    void inventedToolNamesAreCollapsed() {
        for (String invented : new String[] {"transfer_all_funds", "http_request",
                "search_products_v2", "SEARCH_PRODUCTS", null, "", "  ",
                "user_secret_averyrealisticlookingkey"}) {
            assertThat(AgentMetrics.toolTag(invented))
                    .as("invented name %s", invented)
                    .isEqualTo(AgentMetrics.TOOL_UNREGISTERED);
        }
    }

    @Test
    @DisplayName("a credential a model put in a tool name cannot reach the metrics backend")
    void credentialInAToolNameNeverBecomesATag() {
        String secret = "user_secret_averyrealisticlookingplatformkey";

        metrics.toolValidationFailure(secret, "UNKNOWN_TOOL");

        assertThat(registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(io.micrometer.core.instrument.Tag::getValue))
                .doesNotContain(secret)
                .contains(AgentMetrics.TOOL_UNREGISTERED);
    }

    @Test
    @DisplayName("a thousand invented names produce one time series, not a thousand")
    void cardinalityIsBounded() {
        for (int i = 0; i < 1000; i++) {
            metrics.toolCall("invented_tool_" + i, false);
        }

        assertThat(registry.find(AgentMetrics.TOOL_CALLS).counters()).hasSize(1);
        assertThat(registry.find(AgentMetrics.TOOL_CALLS).counter().count()).isEqualTo(1000);
    }

    @Test
    @DisplayName("a provider decision records its source and demo flag, which is what separates the two approvals")
    void providerDecisionsDistinguishDemoFromReal() {
        metrics.providerDecision("razorpay", "APPROVE", "payment_collected", false);
        metrics.providerDecision("razorpay", "APPROVE", "order_accepted", true);

        assertThat(registry.find(AgentMetrics.PROVIDER_DECISIONS)
                .tag("source", "payment_collected").tag("demo", "false").counter().count())
                .isEqualTo(1);
        assertThat(registry.find(AgentMetrics.PROVIDER_DECISIONS)
                .tag("source", "order_accepted").tag("demo", "true").counter().count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("policy decisions are tagged by rule, so a mis-set threshold is visible")
    void policyDecisionsAreTaggedByRule() {
        metrics.policyDecision("REFUSE", "refund-amount-cap", "request_refund");
        metrics.policyDecision("PERMIT", "default-permit", "search_products");

        assertThat(registry.find(AgentMetrics.POLICY_DECISIONS)
                .tag("decision", "REFUSE").tag("rule", "refund-amount-cap").counter().count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("model latency is recorded for failures too — that is when it matters most")
    void latencyIsRecordedForFailures() {
        metrics.llmCall("anthropic", "unavailable", Duration.ofSeconds(30));

        assertThat(registry.find(AgentMetrics.LLM_LATENCY).tag("outcome", "unavailable").timer()
                .totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(30.0);
    }

    @Test
    @DisplayName("no meter carries an amount, an id, or anything per-request")
    void noMeterCarriesPerRequestData() {
        metrics.turnCompleted("COMPLETED");
        metrics.toolCall("complete_checkout", true);
        metrics.paymentAction("CHECKOUT_PAY", "ok");
        metrics.policyDecision("PERMIT", "default-permit", "complete_checkout");
        metrics.approval("required");
        metrics.providerDecision("razorpay", "DECLINE", "order_accepted", false);

        assertThat(registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(io.micrometer.core.instrument.Tag::getKey)
                .distinct())
                .doesNotContain("amount", "amountMinor", "merchantId", "conversationId", "paymentId",
                        "correlationId", "checkoutId");
    }
}
