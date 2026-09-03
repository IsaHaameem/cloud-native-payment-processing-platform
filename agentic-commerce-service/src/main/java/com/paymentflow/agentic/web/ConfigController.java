package com.paymentflow.agentic.web;

import com.paymentflow.agentic.config.AgenticProperties;
import com.paymentflow.agentic.policy.PolicyCatalog;
import com.paymentflow.agentic.runtime.SystemPrompt;
import com.paymentflow.agentic.tool.ToolRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The read-only view of how the agent runtime is actually configured (G-3).
 *
 * <pre>
 *   GET /api/agentic/config
 * </pre>
 *
 * <h2>Reflects the runtime, never a copy of it</h2>
 *
 * <p>Every value comes straight from the objects the runtime uses: {@link AgenticProperties} for
 * the limits and modes, {@link ToolRegistry#specs()} for the tool inventory, {@link PolicyCatalog}
 * for the rules and thresholds ({@link PolicyCatalog} itself reads {@code AgenticProperties} live),
 * {@link SystemPrompt#VERSION} for the prompt version. There is no hand-maintained mirror to go
 * stale.
 *
 * <h2>No secret leaves this endpoint</h2>
 *
 * <p>Credentials are reported only as booleans — {@code Llm.isConfigured()},
 * {@code Razorpay.isConfigured()} — which say whether a real key is present, never what it is. A
 * blank credential means the scripted client runs; that fact is surfaced as {@code scriptedFallback}
 * so the portal can be honest that the demo is not calling a model.
 */
@RestController
@RequestMapping("/api/agentic/config")
public class ConfigController {

    private final AgenticProperties properties;
    private final ToolRegistry toolRegistry;
    private final PolicyCatalog policyCatalog;
    private final AgenticCallerContext callerContext;

    public ConfigController(AgenticProperties properties, ToolRegistry toolRegistry,
                           PolicyCatalog policyCatalog, AgenticCallerContext callerContext) {
        this.properties = properties;
        this.toolRegistry = toolRegistry;
        this.policyCatalog = policyCatalog;
        this.callerContext = callerContext;
    }

    public record ToolView(String name, String category, boolean movesMoney, String description) {
    }

    public record LlmView(
            String provider,
            String model,
            boolean credentialConfigured,
            boolean scriptedFallback,
            int maxToolIterations,
            int maxTurnDurationMs) {
    }

    public record CheckoutView(int ttlMinutes, int maxLineItems) {
    }

    public record RazorpayView(boolean credentialConfigured, boolean enabled, String uncollectedOrderOutcome) {
    }

    public record PolicyView(String version, String currency, List<PolicyCatalog.PolicyRuleView> rules) {
    }

    public record ConfigView(
            String mode,
            String promptVersion,
            LlmView llm,
            CheckoutView checkout,
            RazorpayView razorpay,
            PolicyView policy,
            List<ToolView> tools) {
    }

    @GetMapping
    public ConfigView get() {
        // Resolve (and thereby authenticate + scope) even though the payload is not tenant-specific:
        // an unauthenticated caller has no business reading this service's configuration at all.
        callerContext.resolve();

        AgenticProperties.Llm llm = properties.llm();
        AgenticProperties.Razorpay razorpay = properties.razorpay();
        AgenticProperties.Checkout checkout = properties.checkout();

        List<ToolView> tools = toolRegistry.specs().stream()
                .map(spec -> new ToolView(spec.name(), spec.category().name(), spec.movesMoney(),
                        spec.description()))
                .toList();

        return new ConfigView(
                AgenticCallerContext.MODE,
                SystemPrompt.VERSION,
                new LlmView(llm.provider(), llm.activeModel(), llm.isConfigured(), !llm.isConfigured(),
                        llm.maxToolIterations(), llm.maxTurnDurationMs()),
                new CheckoutView(checkout.ttlMinutes(), checkout.maxLineItems()),
                new RazorpayView(razorpay.isConfigured(), razorpay.enabled(),
                        razorpay.uncollectedOrderOutcome()),
                new PolicyView(policyCatalog.policyVersion(), policyCatalog.currency(),
                        policyCatalog.describe()),
                tools);
    }
}
