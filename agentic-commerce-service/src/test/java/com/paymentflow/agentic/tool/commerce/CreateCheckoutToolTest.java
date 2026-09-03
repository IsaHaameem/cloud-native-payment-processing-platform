package com.paymentflow.agentic.tool.commerce;

import com.paymentflow.agentic.checkout.CheckoutService;
import com.paymentflow.agentic.config.AgenticProperties;
import com.paymentflow.agentic.error.AgenticException;
import com.paymentflow.agentic.policy.PolicyOperation;
import com.paymentflow.agentic.policy.ToolCategory;
import com.paymentflow.agentic.tool.ResolvedAction;
import com.paymentflow.agentic.tool.ToolArguments;
import com.paymentflow.agentic.tool.ToolContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@code create_checkout}'s contract — above all, that there is no argument through which a
 * price can arrive.
 *
 * <p>{@link CheckoutService} is a mock here on purpose: what is being asserted is what the
 * tool will and will not accept, and a real service would let a passing test be explained by
 * the service's own guards rather than by the tool's schema.
 */
class CreateCheckoutToolTest {

    private final CheckoutService checkoutService = mock(CheckoutService.class);
    private final CreateCheckoutTool tool = new CreateCheckoutTool(checkoutService, properties());

    private static final UUID PRODUCT = UUID.fromString("77777777-7777-7777-7777-777777777777");

    @Test
    @DisplayName("the model supplies quantities and product ids, and nothing else")
    void validatesLineItems() {
        CreateCheckoutTool.Input input = tool.validate(arguments(Map.of("items",
                List.of(Map.of("productId", PRODUCT.toString(), "quantity", 2)))));

        assertThat(input.lines()).containsExactly(new CheckoutService.LineRequest(PRODUCT, 2));
    }

    @Test
    @DisplayName("a price in a line item is rejected — this is the tool's whole reason to exist")
    void priceInALineItemIsRejected() {
        assertThatThrownBy(() -> tool.validate(arguments(Map.of("items",
                List.of(Map.of("productId", PRODUCT.toString(), "quantity", 2,
                        "unitPriceMinor", 1))))))
                .isInstanceOf(AgenticException.class)
                .hasMessageContaining("unitPriceMinor");
    }

    @Test
    @DisplayName("a total or discount at the top level is rejected too")
    void totalOrDiscountIsRejected() {
        assertThatThrownBy(() -> tool.validate(arguments(Map.of(
                "items", List.of(Map.of("productId", PRODUCT.toString(), "quantity", 1)),
                "totalMinor", 1))))
                .isInstanceOf(AgenticException.class)
                .hasMessageContaining("totalMinor");

        assertThatThrownBy(() -> tool.validate(arguments(Map.of(
                "items", List.of(Map.of("productId", PRODUCT.toString(), "quantity", 1)),
                "discountMinor", 5000))))
                .isInstanceOf(AgenticException.class)
                .hasMessageContaining("discountMinor");
    }

    @Test
    @DisplayName("a merchant id in the arguments is rejected — tenancy comes from the context")
    void merchantIdIsNotAnArgument() {
        assertThatThrownBy(() -> tool.validate(arguments(Map.of(
                "items", List.of(Map.of("productId", PRODUCT.toString(), "quantity", 1)),
                "merchantId", UUID.randomUUID().toString()))))
                .isInstanceOf(AgenticException.class)
                .hasMessageContaining("merchantId");
    }

    @Test
    @DisplayName("an empty basket is rejected at the schema, not at the database")
    void emptyBasketIsRejected() {
        assertThatThrownBy(() -> tool.validate(arguments(Map.of("items", List.of()))))
                .isInstanceOf(AgenticException.class);
    }

    @Test
    @DisplayName("more line items than the configured maximum is rejected")
    void tooManyLineItemsAreRejected() {
        List<Map<String, Object>> lines = java.util.stream.IntStream.range(0, 21)
                .mapToObj(i -> Map.<String, Object>of("productId", UUID.randomUUID().toString(),
                        "quantity", 1))
                .toList();

        assertThatThrownBy(() -> tool.validate(arguments(Map.of("items", lines))))
                .isInstanceOf(AgenticException.class);
    }

    @Test
    @DisplayName("a quantity beyond the line-item bound is rejected")
    void oversizedQuantityIsRejected() {
        assertThatThrownBy(() -> tool.validate(arguments(Map.of("items",
                List.of(Map.of("productId", PRODUCT.toString(), "quantity", 2000))))))
                .isInstanceOf(AgenticException.class)
                .hasMessageContaining("between 1 and 100");
    }

    @Test
    @DisplayName("resolving touches nothing — a commerce action has no amount for policy to bound")
    void resolveHasNoSideEffects() {
        CreateCheckoutTool.Input input = tool.validate(arguments(Map.of("items",
                List.of(Map.of("productId", PRODUCT.toString(), "quantity", 2)))));

        ResolvedAction resolved = tool.resolve(context(), input);

        assertThat(resolved.target().amountMinor()).isNull();
        assertThat(resolved.target().checkoutId()).isNull();
        assertThat(resolved.description()).contains("1 line");
        verifyNoInteractions(checkoutService);
    }

    @Test
    @DisplayName("the tool is classified as commerce, so no money rule is applied to it")
    void isClassifiedAsCommerce() {
        assertThat(tool.spec().operation()).isEqualTo(PolicyOperation.CHECKOUT_CREATE);
        assertThat(tool.spec().category()).isEqualTo(ToolCategory.COMMERCE);
        assertThat(tool.spec().movesMoney()).isFalse();
    }

    @Test
    @DisplayName("the published schema tells the model that prices are not its to set")
    void schemaDeclaresOnlyProductAndQuantity() {
        Map<String, Object> definition = tool.spec().toLlmDefinition();

        assertThat(definition.get("input_schema").toString())
                .contains("productId")
                .contains("quantity")
                .doesNotContain("price")
                .doesNotContain("amount");
        assertThat(definition.get("description").toString()).contains("cannot set or negotiate a price");
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────────────

    private static ToolArguments arguments(Map<String, Object> raw) {
        return ToolArguments.of("create_checkout", raw);
    }

    private static ToolContext context() {
        return new ToolContext(UUID.randomUUID(), "test", UUID.randomUUID(), "session-1", "principal",
                UUID.randomUUID().toString(), UUID.randomUUID());
    }

    private static AgenticProperties properties() {
        return new AgenticProperties(
                new AgenticProperties.Platform("http://localhost:8080", "sk_test_fixture", 2000, 10000),
                new AgenticProperties.Policy("2026-08-20.1", "INR", 5_000_000L, 10_000_000L, 100_000L,
                        2_000_000L, 5_000_000L, 60, 30),
                new AgenticProperties.Checkout(30, 20),
                new AgenticProperties.Llm("anthropic", "https://example.invalid", "", "model", 2048, 0.2,
                        30000, 8, 120000, "", ""),
                new AgenticProperties.Razorpay(false, "https://example.invalid", "", "", 2000, 8000, "decline"),
                new AgenticProperties.Demo("", false));
    }
}
