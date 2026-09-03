package com.paymentflow.agentic.tool;

import com.paymentflow.agentic.error.AgenticErrorCode;
import com.paymentflow.agentic.error.AgenticException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Argument validation, treated as the hostile-input problem it is.
 *
 * <p>Every rejection below is something a real model does: a number sent as a string, a
 * fractional quantity, an id that is nearly a UUID, a helpfully-added extra field. The point
 * of the tests is not that the code says no, but that it says no <em>before</em> a typed input
 * exists — nothing downstream ever sees a half-valid call.
 */
class ToolArgumentsTest {

    private static final ToolSchema SCHEMA = ToolSchema.builder()
            .string("productId", "the product", true, 64)
            .integer("quantity", "how many", true, 1, 100)
            .string("note", "optional note", false, 20)
            .build();

    private static ToolArguments args(Map<String, Object> raw) {
        return ToolArguments.of("test_tool", raw);
    }

    @Nested
    @DisplayName("unknown arguments")
    class Unknown {

        @Test
        @DisplayName("an argument the schema does not declare is rejected, not ignored")
        void unknownArgumentIsRejected() {
            assertThatThrownBy(() -> args(Map.of("productId", UUID.randomUUID().toString(),
                    "quantity", 1, "amountMinor", 999_999)).requireOnly(SCHEMA))
                    .isInstanceOf(AgenticException.class)
                    .satisfies(e -> assertThat(((AgenticException) e).agenticErrorCode())
                            .isEqualTo(AgenticErrorCode.TOOL_ARGUMENTS_INVALID))
                    .hasMessageContaining("amountMinor");
        }

        @Test
        @DisplayName("the rejection lists what the tool does accept, so the model can correct itself")
        void rejectionNamesTheAcceptedArguments() {
            assertThatThrownBy(() -> args(Map.of("nope", "x")).requireOnly(SCHEMA))
                    .hasMessageContaining("productId")
                    .hasMessageContaining("quantity");
        }

        @Test
        @DisplayName("exactly the declared arguments pass")
        void declaredArgumentsPass() {
            ToolArguments arguments = args(Map.of("productId", UUID.randomUUID().toString(), "quantity", 3))
                    .requireOnly(SCHEMA);

            assertThat(arguments.requireInt("quantity", 1, 100)).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("scalars")
    class Scalars {

        @Test
        @DisplayName("a string that is not a UUID is rejected")
        void malformedUuidIsRejected() {
            assertThatThrownBy(() -> args(Map.of("productId", "not-a-uuid")).requireUuid("productId"))
                    .isInstanceOf(AgenticException.class)
                    .hasMessageContaining("must be a UUID");
        }

        @Test
        @DisplayName("a missing required argument is rejected")
        void missingRequiredArgumentIsRejected() {
            assertThatThrownBy(() -> args(Map.of()).requireUuid("productId"))
                    .hasMessageContaining("is required");
        }

        @Test
        @DisplayName("a blank string is not a value")
        void blankStringIsRejected() {
            assertThatThrownBy(() -> args(Map.of("note", "   ")).requireString("note", 20))
                    .hasMessageContaining("must not be blank");
        }

        @Test
        @DisplayName("an over-long string is rejected rather than truncated")
        void overLongStringIsRejected() {
            assertThatThrownBy(() -> args(Map.of("note", "x".repeat(21))).requireString("note", 20))
                    .hasMessageContaining("at most 20 characters");
        }

        @Test
        @DisplayName("a number sent as a string is accepted — models do this constantly")
        void numericStringIsAccepted() {
            assertThat(args(Map.of("quantity", "7")).requireInt("quantity", 1, 100)).isEqualTo(7);
        }

        @Test
        @DisplayName("a fractional quantity is rejected, never truncated")
        void fractionalNumberIsRejected() {
            assertThatThrownBy(() -> args(Map.of("quantity", 2.7)).requireInt("quantity", 1, 100))
                    .hasMessageContaining("whole number");
        }

        @Test
        @DisplayName("a number outside its declared bounds is rejected")
        void outOfRangeNumberIsRejected() {
            assertThatThrownBy(() -> args(Map.of("quantity", 0)).requireInt("quantity", 1, 100))
                    .hasMessageContaining("between 1 and 100");
            assertThatThrownBy(() -> args(Map.of("quantity", 101)).requireInt("quantity", 1, 100))
                    .hasMessageContaining("between 1 and 100");
        }

        @Test
        @DisplayName("a boolean where a number belongs is rejected")
        void wrongTypeIsRejected() {
            assertThatThrownBy(() -> args(Map.of("quantity", true)).requireInt("quantity", 1, 100))
                    .hasMessageContaining("whole number");
        }

        @Test
        @DisplayName("an absent optional argument is null, not a default guess")
        void absentOptionalIsNull() {
            assertThat(args(Map.of()).optionalString("note", 20)).isNull();
            assertThat(args(Map.of()).optionalLong("quantity", 1, 100)).isNull();
            assertThat(args(Map.of()).optionalUuid("productId")).isNull();
        }

        @Test
        @DisplayName("a present optional argument still has to be valid")
        void presentOptionalIsStillValidated() {
            assertThatThrownBy(() -> args(Map.of("note", "x".repeat(50))).optionalString("note", 20))
                    .isInstanceOf(AgenticException.class);
        }
    }

    @Nested
    @DisplayName("arrays")
    class Arrays {

        @Test
        @DisplayName("each element is validated by the same strict rules as a top-level argument")
        void elementsAreValidatedStrictly() {
            ToolArguments arguments = args(Map.of("items", List.of(
                    Map.of("productId", UUID.randomUUID().toString(), "quantity", 2))));

            List<ToolArguments> items = arguments.requireObjectList("items", 1, 20);

            assertThat(items).hasSize(1);
            assertThat(items.getFirst().requireInt("quantity", 1, 100)).isEqualTo(2);
        }

        @Test
        @DisplayName("an element carrying an undeclared field is rejected — a price cannot hide in a line item")
        void elementWithExtraFieldIsRejected() {
            ToolSchema lineSchema = ToolSchema.builder()
                    .string("productId", "the product", true, 64)
                    .integer("quantity", "how many", true, 1, 100)
                    .build();
            List<ToolArguments> items = args(Map.of("items", List.of(
                    Map.of("productId", UUID.randomUUID().toString(), "quantity", 2,
                            "unitPriceMinor", 1))))
                    .requireObjectList("items", 1, 20);

            assertThatThrownBy(() -> items.getFirst().requireOnly(lineSchema))
                    .hasMessageContaining("unitPriceMinor");
        }

        @Test
        @DisplayName("a non-array is rejected")
        void nonArrayIsRejected() {
            assertThatThrownBy(() -> args(Map.of("items", "one thing")).requireObjectList("items", 1, 20))
                    .hasMessageContaining("must be an array");
        }

        @Test
        @DisplayName("an element that is not an object is rejected, naming its index")
        void nonObjectElementIsRejected() {
            assertThatThrownBy(() -> args(Map.of("items", List.of("nope")))
                    .requireObjectList("items", 1, 20))
                    .hasMessageContaining("items[0]");
        }

        @Test
        @DisplayName("an over-long or empty array is rejected")
        void arraySizeIsBounded() {
            assertThatThrownBy(() -> args(Map.of("items", List.of())).requireObjectList("items", 1, 20))
                    .hasMessageContaining("between 1 and 20");
        }
    }

    @Nested
    @DisplayName("failure messages")
    class FailureMessages {

        @Test
        @DisplayName("a rejection never echoes the value it rejected")
        void messagesDoNotEchoValues() {
            String secret = "sk_test_thisisnotarealkeybutlooksliketone";

            assertThatThrownBy(() -> args(Map.of("productId", secret)).requireUuid("productId"))
                    .isInstanceOf(AgenticException.class)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain(secret));
        }

        @Test
        @DisplayName("a rejection names the tool and the argument, which is what the model needs")
        void messagesNameToolAndArgument() {
            assertThatThrownBy(() -> args(Map.of()).requireUuid("productId"))
                    .hasMessageContaining("test_tool")
                    .hasMessageContaining("productId");
        }
    }
}
