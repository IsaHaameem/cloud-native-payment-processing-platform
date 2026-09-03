package com.paymentflow.agentic.tool.catalog;

import com.paymentflow.agentic.catalog.CatalogService;
import com.paymentflow.agentic.catalog.ProductView;
import com.paymentflow.agentic.policy.PolicyOperation;
import com.paymentflow.agentic.tool.AgentTool;
import com.paymentflow.agentic.tool.ResolvedAction;
import com.paymentflow.agentic.tool.ToolArguments;
import com.paymentflow.agentic.tool.ToolContext;
import com.paymentflow.agentic.tool.ToolResult;
import com.paymentflow.agentic.tool.ToolSchema;
import com.paymentflow.agentic.tool.ToolSpec;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * {@code get_product} — one product, by id.
 *
 * <p>Availability is a field on the result rather than a tool of its own. AD-12 dropped
 * {@code check_inventory} for a reason worth repeating: inventory is enforced server-side when
 * a checkout is created and again when it is paid, so a separate tool would be advisory
 * surface with no authority — a model could consult it, be told yes, and still have the
 * checkout refused a moment later.
 *
 * <p>A product belonging to another merchant is a 404, not a permission error. The distinction
 * matters: telling the model "that exists but is not yours" would leak the existence of
 * another tenant's catalogue through a chat window.
 */
@Component
public class GetProductTool implements AgentTool<GetProductTool.Input> {

    private static final ToolSchema SCHEMA = ToolSchema.builder()
            .string("productId", "The product's id, as returned by search_products.", true, 64)
            .build();

    private static final ToolSpec SPEC = new ToolSpec(
            "get_product",
            "Fetch one product by id, including its price in the currency's minor unit and whether it is "
                    + "currently in stock. Use this when the customer asks about a specific product you have "
                    + "already found.",
            PolicyOperation.CATALOG_READ,
            SCHEMA);

    private final CatalogService catalogService;

    public GetProductTool(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    public record Input(UUID productId) {
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public Input validate(ToolArguments arguments) {
        arguments.requireOnly(SCHEMA);
        return new Input(arguments.requireUuid("productId"));
    }

    @Override
    public ResolvedAction resolve(ToolContext context, Input input) {
        return ResolvedAction.nonFinancial(Map.of("productId", input.productId().toString()),
                "Read product %s.".formatted(input.productId()));
    }

    @Override
    public ToolResult execute(ToolContext context, Input input, ResolvedAction resolved) {
        ProductView product = catalogService.get(context.merchantId(), context.mode(), input.productId());
        return ToolResult.ok(SPEC.name(), product);
    }
}
