package com.paymentflow.agentic.tool;

import com.paymentflow.agentic.error.AgenticErrorCode;
import com.paymentflow.agentic.error.AgenticException;
import com.paymentflow.agentic.policy.PolicyOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The closed set of things the agent can do — the allow-list, in the literal sense.
 *
 * <p>The model is shown {@link #llmDefinitions()} and nothing else. A name that is not in this
 * registry cannot be validated, cannot be resolved, cannot reach the policy engine, and cannot
 * execute: {@link #validate} throws {@link AgenticErrorCode#UNKNOWN_TOOL}, and the policy
 * engine independently refuses an unrecognised tool under {@code PolicyRule.TOOL_ALLOW_LIST}
 * in case a future pipeline ever reaches it another way.
 *
 * <p><b>There is no switch statement anywhere in this service on a tool name.</b> Tools are
 * Spring components; this class collects whatever was registered and dispatches through the
 * {@link AgentTool} interface. Adding a tool is adding a class. Nothing enumerates them, so
 * nothing can forget one.
 *
 * <h2>The two structural refusals</h2>
 *
 * <p>This registry <b>refuses to start</b> — the whole service fails to boot — if a tool is
 * registered whose name or whose arguments would give the model a generic capability. Not a
 * warning, not a filter at call time: a startup failure.
 *
 * <ol>
 *   <li><b>No generic-capability names.</b> {@code http_request}, {@code fetch},
 *       {@code execute}, {@code shell}, {@code eval} and their kind.</li>
 *   <li><b>No generic-capability arguments.</b> No tool may declare a {@code url},
 *       {@code endpoint}, {@code host}, {@code header}, {@code body}, {@code command},
 *       {@code script}, {@code sql} or {@code method} argument, at the top level or inside an
 *       array element.</li>
 * </ol>
 *
 * <p>The second is the one that actually holds the line. A tool called
 * {@code lookup_order_status} that happens to take a {@code url} is a generic HTTP client with
 * a friendly name, and only an argument-level check catches it. Together they make "the LLM
 * cannot issue arbitrary HTTP" a property the build can assert rather than a claim about
 * present intentions.
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /**
     * Name fragments that describe a generic capability rather than a business operation. A
     * tool whose name contains any of them is refused at startup.
     */
    private static final Set<String> FORBIDDEN_NAME_FRAGMENTS = Set.of(
            "http", "fetch", "curl", "request_url", "exec", "shell", "bash", "eval",
            "script", "command", "sql", "file_", "read_file", "write_file", "proxy");

    /**
     * Argument names that would hand the model a transport rather than an operation.
     *
     * <p>{@code query} is deliberately absent: {@code search_products} takes one, and a
     * catalogue search term is not a capability. The list names transports and interpreters,
     * not anything merely free-form.
     */
    private static final Set<String> FORBIDDEN_ARGUMENT_NAMES = Set.of(
            "url", "uri", "endpoint", "host", "hostname", "header", "headers", "body",
            "payload", "command", "cmd", "shell", "script", "sql", "method", "code");

    /**
     * Sorted by name and kept sorted. {@code Map.copyOf} is deliberately <em>not</em> used
     * here: it returns an unordered immutable map, which would silently reintroduce
     * hash-order iteration and with it a prompt whose tool list can shuffle between JVM runs.
     */
    private final SortedMap<String, AgentTool<?>> tools;

    public ToolRegistry(List<AgentTool<?>> registered) {
        SortedMap<String, AgentTool<?>> byName = new TreeMap<>();
        for (AgentTool<?> tool : registered) {
            ToolSpec spec = tool.spec();
            rejectGenericCapability(spec);
            AgentTool<?> clash = byName.put(spec.name(), tool);
            if (clash != null) {
                throw new IllegalStateException(("Two tools are registered under the name '%s' (%s and %s). "
                        + "A tool name is what the action log records and what the model calls; it must "
                        + "identify exactly one tool.")
                        .formatted(spec.name(), clash.getClass().getName(), tool.getClass().getName()));
            }
        }
        this.tools = Collections.unmodifiableSortedMap(byName);

        log.info("agent tool registry initialised with {} tool(s): {}", this.tools.size(),
                this.tools.keySet());
    }

    // ── What the model is shown ─────────────────────────────────────────────────────────

    /** Every registered tool's specification, ordered by name so the prompt is byte-stable. */
    public List<ToolSpec> specs() {
        return tools.values().stream().map(AgentTool::spec).toList();
    }

    /**
     * The tool definitions handed to the model.
     *
     * <p>This is the complete extent of what the model can ask for. Anything it names that is
     * not here is rejected before it reaches a typed input, let alone a policy decision.
     */
    public List<Map<String, Object>> llmDefinitions() {
        return specs().stream().map(ToolSpec::toLlmDefinition).toList();
    }

    public boolean isRegistered(String toolName) {
        return toolName != null && tools.containsKey(toolName);
    }

    /**
     * The operation a tool performs, for assembling a policy request.
     *
     * <p>Empty for an unrecognised tool — which the policy engine reads as
     * {@code TOOL_ALLOW_LIST} and refuses, rather than defaulting to something harmless-looking.
     */
    public Optional<PolicyOperation> operationOf(String toolName) {
        return Optional.ofNullable(tools.get(toolName)).map(tool -> tool.spec().operation());
    }

    // ── Proposal to validated call ──────────────────────────────────────────────────────

    /**
     * Stage two of the pipeline: a proposed tool call becomes a validated one, or is rejected.
     *
     * <p>Nothing is resolved and nothing is executed here. The returned
     * {@link ValidatedToolCall} is inert until the orchestrator resolves it, evaluates policy
     * on the resolution, and only then executes.
     *
     * @throws AgenticException {@code UNKNOWN_TOOL} if the model named something not
     *                          registered; {@code TOOL_ARGUMENTS_INVALID} if the arguments do
     *                          not satisfy the tool's schema
     */
    public ValidatedToolCall validate(String toolName, Map<String, Object> arguments) {
        AgentTool<?> tool = tools.get(toolName);
        if (tool == null) {
            throw new AgenticException(AgenticErrorCode.UNKNOWN_TOOL,
                    "No tool named '%s' is registered. Available tools: %s."
                            .formatted(sanitise(toolName), tools.keySet()));
        }
        Map<String, Object> safeArguments = arguments == null ? Map.of() : new LinkedHashMap<>(arguments);
        Object input = tool.validate(ToolArguments.of(toolName, safeArguments));
        return new ValidatedToolCall(tool, input, safeArguments);
    }

    // ── Startup guards ──────────────────────────────────────────────────────────────────

    private static void rejectGenericCapability(ToolSpec spec) {
        String name = spec.name().toLowerCase(Locale.ROOT);
        for (String fragment : FORBIDDEN_NAME_FRAGMENTS) {
            if (name.contains(fragment)) {
                throw new IllegalStateException(("Tool '%s' is refused: its name contains '%s', which describes "
                        + "a generic capability rather than a business operation. This service exposes no "
                        + "generic HTTP, shell or code-execution tool to the model, by construction.")
                        .formatted(spec.name(), fragment));
            }
        }
        rejectGenericArguments(spec.name(), spec.schema());
    }

    /** Recurses into array element schemas — a transport argument hidden one level down is still one. */
    private static void rejectGenericArguments(String toolName, ToolSchema schema) {
        for (ToolSchema.Property property : schema.properties()) {
            String argument = property.name().toLowerCase(Locale.ROOT);
            if (FORBIDDEN_ARGUMENT_NAMES.contains(argument)) {
                throw new IllegalStateException(("Tool '%s' is refused: it declares an argument named '%s'. "
                        + "A tool that accepts a transport — a URL, a host, headers, a body, a command or a "
                        + "query language — is a generic capability whatever it is called, and the model is "
                        + "given none.")
                        .formatted(toolName, property.name()));
            }
            if (property.itemSchema() != null) {
                rejectGenericArguments(toolName, property.itemSchema());
            }
        }
    }

    /**
     * Bounds a model-supplied name before it enters an error message that will be logged and
     * returned to the model. The name is unvalidated at this point — it is whatever the model
     * emitted — so it is truncated rather than echoed whole.
     */
    private static String sanitise(String toolName) {
        if (toolName == null) {
            return "(none)";
        }
        String trimmed = toolName.replaceAll("[^A-Za-z0-9_.-]", "");
        return trimmed.length() <= 64 ? trimmed : trimmed.substring(0, 64);
    }
}
