package com.paymentflow.agentic.tool;

import com.paymentflow.agentic.policy.PolicyOperation;
import com.paymentflow.agentic.policy.ToolCategory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Everything about a tool that is not behaviour: its stable name, what it is for, what it may
 * do, and what it accepts.
 *
 * <p><b>The policy classification lives here, on the declaration, not at the call site.</b> A
 * tool cannot be invoked without its {@link PolicyOperation} travelling with it, so there is
 * no code path where a money tool is evaluated as though it were a read. The category the
 * policy engine branches on is read off the operation, so the two can never be set
 * inconsistently.
 *
 * @param name        the stable identifier the model calls and the action log records. Renaming
 *                    one is a breaking change to every stored {@code agent_actions.tool_name}
 *                    and to any approval reasoning that quoted it, which is why an approval
 *                    binds to the {@link PolicyOperation} instead.
 * @param description what the model is told. Written for a model, not for a maintainer: it has
 *                    to be enough to choose the tool correctly and no more, because every word
 *                    of it is in the prompt on every turn.
 */
public record ToolSpec(String name, String description, PolicyOperation operation, ToolSchema schema) {

    /**
     * Tool names are lowercase snake_case, three to sixty-four characters.
     *
     * <p>Bounded at 64 because {@code agent_actions.tool_name} is {@code varchar(64)} — a name
     * that cannot be recorded is a name whose actions cannot be audited.
     */
    private static final Pattern VALID_NAME = Pattern.compile("^[a-z][a-z0-9_]{2,63}$");

    public ToolSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(schema, "schema");
        if (!VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Tool name '" + name + "' must be lowercase snake_case, 3-64 characters.");
        }
        if (description.isBlank()) {
            throw new IllegalArgumentException("Tool '" + name + "' must describe itself to the model.");
        }
    }

    /** The risk class the policy engine applies, derived from the operation rather than declared twice. */
    public ToolCategory category() {
        return operation.category();
    }

    public boolean movesMoney() {
        return operation.movesMoney();
    }

    /**
     * The tool as the model is shown it: name, description, input schema.
     *
     * <p>This is the <em>entire</em> surface the model sees. There is no field here for a URL,
     * a host, a command or a raw request body, because no tool in this service accepts one —
     * see {@link ToolRegistry} for the check that keeps it that way.
     */
    public Map<String, Object> toLlmDefinition() {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("name", name);
        definition.put("description", description);
        definition.put("input_schema", schema.toJsonSchema());
        return definition;
    }
}
