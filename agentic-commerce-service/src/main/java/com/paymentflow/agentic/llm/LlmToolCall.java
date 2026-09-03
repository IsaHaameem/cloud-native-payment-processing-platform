package com.paymentflow.agentic.llm;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A tool the model asked for. <b>A proposal, and nothing more.</b>
 *
 * <p>Every field here is untrusted. The {@code name} may not be a registered tool; the
 * {@code arguments} may not satisfy its schema; both arrived over a network from a model that
 * was, at best, trying to be helpful. Nothing downstream treats any of it as valid until
 * {@code ToolRegistry.validate} has turned it into a typed input — and a call that fails there
 * never becomes an action at all.
 *
 * @param id        the provider's identifier for this call, echoed back on the matching result
 *                  so the model can pair them. Opaque to this service
 * @param arguments the raw argument map, exactly as the provider parsed it. Never read directly
 *                  by anything except {@code ToolArguments}
 */
public record LlmToolCall(String id, String name, Map<String, Object> arguments) {

    public LlmToolCall {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        arguments = arguments == null ? Map.of() : new LinkedHashMap<>(arguments);
    }
}
