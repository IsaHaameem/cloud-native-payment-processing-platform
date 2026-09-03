package com.paymentflow.agentic.tool;

import com.paymentflow.agentic.error.AgenticErrorCode;
import com.paymentflow.agentic.error.AgenticException;
import com.paymentflow.agentic.policy.PolicyOperation;
import com.paymentflow.agentic.policy.ToolCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The registry's job is to be a wall, and these are the tests that prove it is one.
 *
 * <p>The two startup-refusal tests matter most. They are what turns "the LLM cannot issue
 * arbitrary HTTP" from a statement about the tools that happen to exist today into a property
 * the build enforces about every tool that will ever exist: a contributor who adds a generic
 * capability does not ship a subtle hole, they ship a service that will not start.
 */
class ToolRegistryTest {

    // ── A minimal tool, used to build registries in the tests below ──────────────────────

    private static final class StubTool implements AgentTool<Map<String, Object>> {

        private final ToolSpec spec;

        StubTool(String name, PolicyOperation operation, ToolSchema schema) {
            this.spec = new ToolSpec(name, "a stub tool for testing", operation, schema);
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

    private static ToolSchema emptySchema() {
        return ToolSchema.builder().build();
    }

    private static ToolRegistry registryWith(AgentTool<?>... tools) {
        return new ToolRegistry(List.of(tools));
    }

    @Nested
    @DisplayName("the allow-list")
    class AllowList {

        @Test
        @DisplayName("an unregistered tool is rejected before any typed input exists")
        void unknownToolIsRejected() {
            ToolRegistry registry = registryWith(
                    new StubTool("search_products", PolicyOperation.CATALOG_READ, emptySchema()));

            assertThatThrownBy(() -> registry.validate("transfer_all_funds", Map.of()))
                    .isInstanceOf(AgenticException.class)
                    .satisfies(e -> assertThat(((AgenticException) e).agenticErrorCode())
                            .isEqualTo(AgenticErrorCode.UNKNOWN_TOOL));
        }

        @Test
        @DisplayName("an unknown-tool message does not echo unbounded model text back into the log")
        void unknownToolNameIsSanitised() {
            ToolRegistry registry = registryWith(
                    new StubTool("search_products", PolicyOperation.CATALOG_READ, emptySchema()));

            assertThatThrownBy(() -> registry.validate("x".repeat(500) + " <script>", Map.of()))
                    .satisfies(e -> assertThat(e.getMessage().length()).isLessThan(300));
        }

        @Test
        @DisplayName("the operation of an unregistered tool is empty, which policy reads as a refusal")
        void unknownToolHasNoOperation() {
            ToolRegistry registry = registryWith(
                    new StubTool("search_products", PolicyOperation.CATALOG_READ, emptySchema()));

            assertThat(registry.operationOf("search_products")).contains(PolicyOperation.CATALOG_READ);
            assertThat(registry.operationOf("http_get")).isEmpty();
            assertThat(registry.isRegistered("http_get")).isFalse();
        }

        @Test
        @DisplayName("two tools cannot share a name")
        void duplicateNamesAreRefusedAtStartup() {
            assertThatThrownBy(() -> registryWith(
                    new StubTool("get_product", PolicyOperation.CATALOG_READ, emptySchema()),
                    new StubTool("get_product", PolicyOperation.CATALOG_READ, emptySchema())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("get_product");
        }
    }

    @Nested
    @DisplayName("no generic capability")
    class NoGenericCapability {

        @Test
        @DisplayName("a tool whose name describes a transport refuses to register")
        void genericNamesAreRefusedAtStartup() {
            for (String name : List.of("http_request", "fetch_url", "exec_command", "run_shell", "eval_code",
                    "run_script", "sql_select")) {
                assertThatThrownBy(() -> registryWith(
                        new StubTool(name, PolicyOperation.CATALOG_READ, emptySchema())))
                        .as("tool named %s", name)
                        .isInstanceOf(IllegalStateException.class);
            }
        }

        @Test
        @DisplayName("a friendly-sounding tool that takes a URL still refuses to register")
        void transportArgumentsAreRefusedAtStartup() {
            for (String argument : List.of("url", "uri", "endpoint", "host", "headers", "body", "command",
                    "script", "sql", "method")) {
                ToolSchema schema = ToolSchema.builder()
                        .string(argument, "a transport argument", true, 200)
                        .build();

                assertThatThrownBy(() -> registryWith(
                        new StubTool("lookup_order_status", PolicyOperation.CATALOG_READ, schema)))
                        .as("tool taking argument %s", argument)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining(argument);
            }
        }

        @Test
        @DisplayName("a transport argument hidden inside an array element is caught too")
        void nestedTransportArgumentsAreRefused() {
            ToolSchema itemSchema = ToolSchema.builder()
                    .string("url", "sneaky", true, 200)
                    .build();
            ToolSchema schema = ToolSchema.builder()
                    .arrayOfObjects("items", "items", true, 10, itemSchema)
                    .build();

            assertThatThrownBy(() -> registryWith(
                    new StubTool("create_thing", PolicyOperation.CHECKOUT_CREATE, schema)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("url");
        }

        @Test
        @DisplayName("an ordinary search term is not a transport — 'query' stays allowed")
        void ordinaryArgumentsAreNotRefused() {
            ToolSchema schema = ToolSchema.builder()
                    .string("query", "search words", false, 200)
                    .integer("limit", "how many", false, 1, 20)
                    .build();

            ToolRegistry registry = registryWith(
                    new StubTool("search_products", PolicyOperation.CATALOG_READ, schema));

            assertThat(registry.isRegistered("search_products")).isTrue();
        }
    }

    @Nested
    @DisplayName("what the model is shown")
    class LlmSurface {

        @Test
        @DisplayName("the model is shown every registered tool, and only registered tools")
        void definitionsMatchTheRegistry() {
            ToolRegistry registry = registryWith(
                    new StubTool("get_product", PolicyOperation.CATALOG_READ, emptySchema()),
                    new StubTool("create_checkout", PolicyOperation.CHECKOUT_CREATE, emptySchema()));

            List<Map<String, Object>> definitions = registry.llmDefinitions();

            assertThat(definitions).hasSize(2);
            assertThat(definitions.stream().map(d -> d.get("name")))
                    .containsExactly("create_checkout", "get_product");
            assertThat(definitions.getFirst()).containsOnlyKeys("name", "description", "input_schema");
        }

        @Test
        @DisplayName("the tool list is ordered by name, so the prompt is byte-stable between runs")
        void definitionsAreOrdered() {
            ToolRegistry registry = registryWith(
                    new StubTool("zzz_last", PolicyOperation.CATALOG_READ, emptySchema()),
                    new StubTool("aaa_first", PolicyOperation.CATALOG_READ, emptySchema()));

            assertThat(registry.specs().stream().map(ToolSpec::name))
                    .containsExactly("aaa_first", "zzz_last");
        }

        @Test
        @DisplayName("no tool definition mentions a transport anywhere in its schema")
        void noDefinitionOffersATransport() {
            ToolSchema schema = ToolSchema.builder()
                    .string("query", "search words", false, 200)
                    .build();
            ToolRegistry registry = registryWith(
                    new StubTool("search_products", PolicyOperation.CATALOG_READ, schema));

            Set<String> forbidden = Set.of("url", "endpoint", "host", "header", "command", "shell", "sql");
            for (Map<String, Object> definition : registry.llmDefinitions()) {
                String rendered = definition.get("input_schema").toString().toLowerCase(Locale.ROOT);
                assertThat(forbidden).noneSatisfy(term -> assertThat(rendered).contains(term));
            }
        }
    }

    @Nested
    @DisplayName("validation is not execution")
    class Staging {

        @Test
        @DisplayName("validating a call does not resolve or execute it")
        void validateIsInert() {
            ToolSchema schema = ToolSchema.builder()
                    .string("note", "a note", false, 20)
                    .build();
            ToolRegistry registry = registryWith(
                    new StubTool("get_product", PolicyOperation.CATALOG_READ, schema));

            ValidatedToolCall call = registry.validate("get_product", Map.of("note", "hello"));

            assertThat(call.toolName()).isEqualTo("get_product");
            assertThat(call.spec().category()).isEqualTo(ToolCategory.READ);
            assertThat(call.arguments()).containsEntry("note", "hello");
        }

        @Test
        @DisplayName("a schema violation stops the call before a validated call object exists")
        void schemaViolationStopsValidation() {
            ToolRegistry registry = registryWith(
                    new StubTool("get_product", PolicyOperation.CATALOG_READ, emptySchema()));

            assertThatThrownBy(() -> registry.validate("get_product", Map.of("amountMinor", 100)))
                    .isInstanceOf(AgenticException.class)
                    .satisfies(e -> assertThat(((AgenticException) e).agenticErrorCode())
                            .isEqualTo(AgenticErrorCode.TOOL_ARGUMENTS_INVALID));
        }
    }
}
