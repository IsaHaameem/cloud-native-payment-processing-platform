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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code search_products} — free-text search over this merchant's catalogue.
 *
 * <p>A blank query lists the catalogue rather than matching nothing, which is why AD-12 could
 * drop a separate {@code list_products} tool: "what do you sell?" is a legitimate first
 * question and should not require the model to invent a keyword.
 *
 * <p>The merchant and mode come from {@link ToolContext}, never from an argument, so the
 * search is confined to the caller's own tenant by construction rather than by a filter
 * someone has to remember to apply.
 */
@Component
public class SearchProductsTool implements AgentTool<SearchProductsTool.Input> {

    /** Matches {@code CatalogService}'s own ceiling. A tool result is prompt context, and that is a budget. */
    private static final int MAX_LIMIT = 20;
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_QUERY_LENGTH = 200;

    private static final ToolSchema SCHEMA = ToolSchema.builder()
            .string("query", "Words to search for in the product name, description or category. "
                    + "Omit or leave empty to list the catalogue.", false, MAX_QUERY_LENGTH)
            .integer("limit", "Maximum number of products to return (1-" + MAX_LIMIT + "). Defaults to "
                    + DEFAULT_LIMIT + ".", false, 1, MAX_LIMIT)
            .build();

    private static final ToolSpec SPEC = new ToolSpec(
            "search_products",
            "Search the merchant's product catalogue by keyword. Returns products with their price in the "
                    + "currency's minor unit and whether they are in stock. Use this to find what the customer "
                    + "is asking about before creating a checkout.",
            PolicyOperation.CATALOG_READ,
            SCHEMA);

    private final CatalogService catalogService;

    public SearchProductsTool(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /** What a search returns. A record, so the model sees a shape this service chose. */
    public record Result(List<ProductView> products, int count) {
    }

    public record Input(String query, int limit) {
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public Input validate(ToolArguments arguments) {
        arguments.requireOnly(SCHEMA);
        String query = arguments.optionalString("query", MAX_QUERY_LENGTH);
        Long limit = arguments.optionalLong("limit", 1, MAX_LIMIT);
        return new Input(query, limit == null ? DEFAULT_LIMIT : Math.toIntExact(limit));
    }

    @Override
    public ResolvedAction resolve(ToolContext context, Input input) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("query", input.query());
        summary.put("limit", input.limit());
        return ResolvedAction.nonFinancial(summary, "Search the catalogue for up to %d product(s)."
                .formatted(input.limit()));
    }

    @Override
    public ToolResult execute(ToolContext context, Input input, ResolvedAction resolved) {
        List<ProductView> products = catalogService.search(
                context.merchantId(), context.mode(), input.query(), null, input.limit());
        return ToolResult.ok(SPEC.name(), new Result(products, products.size()));
    }
}
