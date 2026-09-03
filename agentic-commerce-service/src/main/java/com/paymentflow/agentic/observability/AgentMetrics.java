package com.paymentflow.agentic.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Every meter this service emits, declared in one place.
 *
 * <h2>The rule that governs every tag here</h2>
 *
 * <p><b>A tag value must come from a closed set this service defines.</b> Tool names, policy
 * rule ids, decisions, outcomes and provider sources are all bounded vocabularies — seven tools,
 * seventeen rules, three decisions — so tagging by them is safe and useful.
 *
 * <p>What is <em>not</em> safe is the one string on this path that a model supplies: the name of
 * a tool it asked for. An unregistered tool name is arbitrary text from a language model, and
 * tagging a metric with it would be two bugs at once — an unbounded-cardinality time series that
 * grows every time the model invents a name, and a channel through which model-authored text
 * (which could contain anything, including a credential the model was handed by a user) lands in
 * a metrics backend that nobody thinks of as a log. {@link #toolTag} collapses every
 * unregistered name to a single constant for exactly that reason.
 *
 * <p>No amount, merchant id, conversation id, payment id or correlation id is ever a tag either.
 * Those are per-request identifiers; they belong in logs and in the action trail, and a metric
 * tagged with one is a metric with a cardinality equal to the number of requests.
 */
@Component
public class AgentMetrics {

    // ── Meter names ─────────────────────────────────────────────────────────────────────

    static final String AGENT_TURNS = "agentic_agent_turns_total";
    static final String LLM_REQUESTS = "agentic_llm_requests_total";
    static final String LLM_LATENCY = "agentic_llm_request_duration";
    static final String TOOL_CALLS = "agentic_tool_calls_total";
    static final String TOOL_VALIDATION_FAILURES = "agentic_tool_validation_failures_total";
    static final String POLICY_DECISIONS = "agentic_policy_decisions_total";
    static final String APPROVALS = "agentic_approvals_total";
    static final String PAYMENT_ACTIONS = "agentic_payment_actions_total";
    static final String PROVIDER_DECISIONS = "agentic_provider_decisions_total";

    /** What an unregistered tool name is reported as. Never the name itself. */
    static final String TOOL_UNREGISTERED = "unregistered";

    /** A registered tool name is lowercase snake_case, matching {@code ToolSpec}'s own rule. */
    private static final Pattern REGISTERED_NAME = Pattern.compile("^[a-z][a-z0-9_]{2,63}$");

    /**
     * The tool names that may appear as a tag.
     *
     * <p>An explicit list rather than a lookup against the live registry, so this class has no
     * dependency on it and cannot be made to emit a tag by registering something. Anything not
     * here is {@link #TOOL_UNREGISTERED}.
     */
    private static final Set<String> KNOWN_TOOLS = Set.of(
            "search_products", "get_product", "create_checkout", "complete_checkout",
            "request_refund", "get_payment_status", "explain_payment_outcome");

    private final MeterRegistry registry;

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    // ── Agent turns ─────────────────────────────────────────────────────────────────────

    /** One completed turn, tagged by why it stopped. */
    public void turnCompleted(String stopReason) {
        registry.counter(AGENT_TURNS, "stop_reason", safe(stopReason)).increment();
    }

    // ── The model ───────────────────────────────────────────────────────────────────────

    /**
     * One model call, its latency and its outcome.
     *
     * <p>Latency is recorded for failures as well as successes: a provider that is timing out is
     * one whose latency distribution is the most interesting thing about it, and dropping those
     * samples would make the graph look healthiest exactly when it is not.
     */
    public void llmCall(String provider, String outcome, Duration duration) {
        registry.counter(LLM_REQUESTS, "provider", safe(provider), "outcome", safe(outcome))
                .increment();
        Timer.builder(LLM_LATENCY)
                .description("Time spent waiting for the language model, including failures.")
                .tag("provider", safe(provider))
                .tag("outcome", safe(outcome))
                .register(registry)
                .record(duration);
    }

    // ── Tools ───────────────────────────────────────────────────────────────────────────

    /** One tool execution attempt that got as far as running. */
    public void toolCall(String toolName, boolean ok) {
        registry.counter(TOOL_CALLS, "tool", toolTag(toolName), "outcome", ok ? "ok" : "failed")
                .increment();
    }

    /**
     * A call rejected before it became an action — an unknown tool, or arguments that did not
     * satisfy the schema.
     *
     * <p>{@code reason} is one of this service's own error codes, never the rejection message,
     * which is assembled per call and would be unbounded.
     */
    public void toolValidationFailure(String toolName, String reason) {
        registry.counter(TOOL_VALIDATION_FAILURES, "tool", toolTag(toolName), "reason", safe(reason))
                .increment();
    }

    // ── Policy and approval ─────────────────────────────────────────────────────────────

    /**
     * One policy evaluation.
     *
     * <p>Tagged by rule as well as decision, because "we refused things" and "we refused things
     * for <em>this</em> reason" are different questions, and only the second one tells an
     * operator whether a threshold is set wrong.
     */
    public void policyDecision(String decision, String ruleId, String toolName) {
        registry.counter(POLICY_DECISIONS,
                "decision", safe(decision),
                "rule", safe(ruleId),
                "tool", toolTag(toolName)).increment();
    }

    /** An approval was opened, granted, denied, expired, or failed to redeem. */
    public void approval(String event) {
        registry.counter(APPROVALS, "event", safe(event)).increment();
    }

    // ── Money ───────────────────────────────────────────────────────────────────────────

    /**
     * A money action that reached execution.
     *
     * <p>{@code operation} is the policy operation — {@code CHECKOUT_PAY} or
     * {@code REFUND_CREATE} — and {@code outcome} distinguishes success from the kinds of
     * failure worth alerting on separately. <b>No amount is recorded</b>: a metric is not a
     * ledger, and the ledger already exists.
     */
    public void paymentAction(String operation, String outcome) {
        registry.counter(PAYMENT_ACTIONS, "operation", safe(operation), "outcome", safe(outcome))
                .increment();
    }

    // ── Provider ────────────────────────────────────────────────────────────────────────

    /**
     * One acquirer decision.
     *
     * <p>{@code source} is tagged deliberately, and it is the most operationally interesting tag
     * in this class: it is what separates {@code payment_collected} — a real cardholder
     * authorization — from {@code order_accepted}, which is the demonstration stand-in. A
     * dashboard showing approvals without this tag would show demo approvals and real ones as
     * the same bar.
     */
    public void providerDecision(String provider, String outcome, String source, boolean demo) {
        registry.counter(PROVIDER_DECISIONS,
                "provider", safe(provider),
                "outcome", safe(outcome),
                "source", safe(source),
                "demo", Boolean.toString(demo)).increment();
    }

    // ── Tag safety ──────────────────────────────────────────────────────────────────────

    /**
     * Collapses any tool name that is not a registered one to a single constant.
     *
     * <p>This is the method that stops model-authored text reaching the metrics backend. See the
     * class javadoc for why that matters more than it first appears.
     */
    static String toolTag(String toolName) {
        if (toolName == null) {
            return TOOL_UNREGISTERED;
        }
        // Matched exactly, with no case normalisation. ToolRegistry resolves by exact name, so
        // `SEARCH_PRODUCTS` is an unregistered tool that was rejected — and a metric that
        // normalised it would claim a registered tool had run when none did. The tag has to say
        // what happened, not what the model probably meant.
        return REGISTERED_NAME.matcher(toolName).matches() && KNOWN_TOOLS.contains(toolName)
                ? toolName
                : TOOL_UNREGISTERED;
    }

    /**
     * Bounds any other tag value.
     *
     * <p>Everything passed here already comes from a closed set, so this is a backstop rather
     * than the primary defence — but a backstop on the path where an unbounded tag would be
     * expensive and silent is worth its four lines.
     */
    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String cleaned = value.replaceAll("[^A-Za-z0-9_.:-]", "_");
        return cleaned.length() <= 64 ? cleaned : cleaned.substring(0, 64);
    }
}
