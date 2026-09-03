package com.paymentflow.agentic.tool;

import com.paymentflow.agentic.policy.PolicyOperation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ValidatedToolCall#arguments()} is documented as the action log's source for a
 * redacted summary. This test is what keeps that documentation honest: a caller that gets hold
 * of the map cannot rewrite what the log will say a validated call's arguments actually were.
 */
class ValidatedToolCallTest {

    // A minimal tool, same shape as ToolRegistryTest's — just enough to produce a
    // ValidatedToolCall through the real path, ToolRegistry.validate().
    private static final class StubTool implements AgentTool<Map<String, Object>> {

        private final ToolSpec spec;

        StubTool(String name, ToolSchema schema) {
            this.spec = new ToolSpec(name, "a stub tool for testing", PolicyOperation.CATALOG_READ, schema);
        }

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public Map<String, Object> validate(ToolArguments arguments) {
            return arguments.requireOnly(spec.schema()).asMap();
        }

        @Override
        public ResolvedAction resolve(ToolContext context, Map<String, Object> input) {
            return ResolvedAction.nonFinancial(input, "stub");
        }

        @Override
        public ToolResult execute(ToolContext context, Map<String, Object> input, ResolvedAction resolved) {
            return ToolResult.ok(spec.name(), input);
        }
    }

    @Test
    @DisplayName("a caller cannot mutate the arguments a validated call carries")
    void argumentsCannotBeMutatedThroughTheReturnedMap() {
        ToolSchema schema = ToolSchema.builder()
                .string("note", "a note", false, 20)
                .build();
        ToolRegistry registry = new ToolRegistry(List.of(new StubTool("get_product", schema)));

        ValidatedToolCall call = registry.validate("get_product", Map.of("note", "hello"));

        assertThatThrownBy(() -> call.arguments().put("note", "tampered"))
                .isInstanceOf(UnsupportedOperationException.class);
        // The rejected attempt did not partially land — what the log will summarise is untouched.
        assertThat(call.arguments()).containsEntry("note", "hello");
    }
}
