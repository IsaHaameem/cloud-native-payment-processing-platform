package com.paymentflow.agentic.tool.payment;

import com.paymentflow.agentic.platform.PaymentFlowClient;
import com.paymentflow.agentic.platform.PaymentView;
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
 * {@code get_payment_status} — what the platform currently says about a payment.
 *
 * <p><b>This tool is the reason the agent never has to guess.</b> The model is not the source
 * of truth for payment status, and this is the mechanism that makes that a fact rather than an
 * instruction: every status the agent reports came back through here from a {@code GET} the
 * platform served. If this call fails, the honest answer is that the status is unknown, not a
 * recollection of what it was last turn.
 *
 * <p>Merchant scoping is the platform's. A payment belonging to someone else is a 404 there,
 * masked exactly as it would be for any other integrator — this service adds no check of its
 * own because it has no privileged view to check against.
 */
@Component
public class GetPaymentStatusTool implements AgentTool<GetPaymentStatusTool.Input> {

    private static final ToolSchema SCHEMA = ToolSchema.builder()
            .string("paymentId", "The payment to look up.", true, 64)
            .build();

    private static final ToolSpec SPEC = new ToolSpec(
            "get_payment_status",
            "Look up the current state of a payment: its status, the amount authorized, how much has been "
                    + "captured and how much refunded. Use this rather than recalling what a previous step "
                    + "returned — the payment may have moved since.",
            PolicyOperation.PAYMENT_READ,
            SCHEMA);

    private final PaymentFlowClient platform;

    public GetPaymentStatusTool(PaymentFlowClient platform) {
        this.platform = platform;
    }

    public record Input(UUID paymentId) {
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public Input validate(ToolArguments arguments) {
        arguments.requireOnly(SCHEMA);
        return new Input(arguments.requireUuid("paymentId"));
    }

    @Override
    public ResolvedAction resolve(ToolContext context, Input input) {
        return ResolvedAction.nonFinancial(Map.of("paymentId", input.paymentId().toString()),
                "Read the current state of payment %s.".formatted(input.paymentId()));
    }

    @Override
    public ToolResult execute(ToolContext context, Input input, ResolvedAction resolved) {
        PaymentView payment = platform.getPayment(context.correlationId(), input.paymentId()).body();
        return ToolResult.ok(SPEC.name(), payment);
    }
}
