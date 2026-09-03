package com.paymentflow.agentic.tool.commerce;

import com.paymentflow.agentic.checkout.Checkout;
import com.paymentflow.agentic.checkout.CheckoutService;
import com.paymentflow.agentic.checkout.CheckoutView;
import com.paymentflow.agentic.config.AgenticProperties;
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
import java.util.stream.Collectors;

/**
 * {@code create_checkout} — turns a list of products and quantities into a priced, server-owned
 * quote.
 *
 * <p><b>The model supplies quantities. It does not supply prices, and there is no argument
 * through which it could.</b> The schema declares exactly two fields per line — a product id
 * and a quantity — so a price, a discount or a total in a tool call is not merely ignored but
 * rejected outright by {@link ToolArguments#requireOnly}. Everything financial about the
 * resulting checkout is computed by {@link CheckoutService} from the catalogue's own rows, and
 * the database re-asserts it: {@code chk_checkouts_total_is_derived} makes a checkout whose
 * total disagrees with its own line items unrepresentable.
 *
 * <p>This is the tool that makes the payment tool safe. {@code complete_checkout} takes a
 * checkout id and nothing else, so the only way an amount reaches a payment is by having been
 * derived here first.
 */
@Component
public class CreateCheckoutTool implements AgentTool<CreateCheckoutTool.Input> {

    /** Mirrors {@code chk_checkout_items_quantity_bounded}. A wall an agent hits well before a policy cap. */
    private static final int MAX_QUANTITY = 100;

    private static final ToolSchema LINE_SCHEMA = ToolSchema.builder()
            .string("productId", "The product's id, as returned by search_products.", true, 64)
            .integer("quantity", "How many units of this product (1-" + MAX_QUANTITY + ").", true, 1,
                    MAX_QUANTITY)
            .build();

    private final CheckoutService checkoutService;
    private final ToolSpec spec;
    private final ToolSchema schema;

    public CreateCheckoutTool(CheckoutService checkoutService, AgenticProperties properties) {
        this.checkoutService = checkoutService;
        int maxLineItems = properties.checkout().maxLineItems();
        this.schema = ToolSchema.builder()
                .arrayOfObjects("items", "The products and quantities to buy. Prices are set by the "
                        + "merchant's catalogue and must not be supplied.", true, maxLineItems, LINE_SCHEMA)
                .build();
        this.spec = new ToolSpec(
                "create_checkout",
                "Create a checkout for the given products and quantities. The total is calculated by the "
                        + "merchant from its own catalogue prices — you cannot set or negotiate a price. "
                        + "Returns the checkout id, its line items and the total the customer will pay.",
                PolicyOperation.CHECKOUT_CREATE,
                schema);
    }

    public record Input(List<CheckoutService.LineRequest> lines) {
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public Input validate(ToolArguments arguments) {
        arguments.requireOnly(schema);
        List<ToolArguments> items = arguments.requireObjectList("items", 1, maxLineItems());
        List<CheckoutService.LineRequest> lines = items.stream()
                .map(item -> {
                    item.requireOnly(LINE_SCHEMA);
                    return new CheckoutService.LineRequest(
                            item.requireUuid("productId"),
                            item.requireInt("quantity", 1, MAX_QUANTITY));
                })
                .toList();
        return new Input(lines);
    }

    /**
     * Describes the intent without touching the catalogue.
     *
     * <p>Nothing is created and nothing is priced here. Pricing twice — once to preview and
     * once to create — would mean the preview and the checkout could disagree if a catalogue
     * price moved between them, and the model would have quoted the one that is not
     * authoritative. A commerce action has no amount for the policy engine to bound, so there
     * is nothing that needs resolving before the decision.
     */
    @Override
    public ResolvedAction resolve(ToolContext context, Input input) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("lineCount", input.lines().size());
        summary.put("items", input.lines().stream()
                .map(line -> line.productId() + "x" + line.quantity())
                .collect(Collectors.joining(",")));
        return ResolvedAction.nonFinancial(summary,
                "Create a checkout with %d line(s), priced from the merchant's catalogue."
                        .formatted(input.lines().size()));
    }

    @Override
    public ToolResult execute(ToolContext context, Input input, ResolvedAction resolved) {
        Checkout checkout = checkoutService.create(context.merchantId(), context.mode(),
                context.conversationId(), context.sessionRef(), input.lines());
        return ToolResult.ok(spec.name(), CheckoutView.of(checkout));
    }

    private int maxLineItems() {
        return schema.properties().getFirst().maxItems();
    }
}
