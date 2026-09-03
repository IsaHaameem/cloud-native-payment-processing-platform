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

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * {@code explain_payment_outcome} — the platform's reason, and what a buyer can do about it.
 *
 * <p><b>The explanation is a lookup, not a generation.</b> This tool maps the acquirer's own
 * {@code failureReason} onto a fixed, server-side sentence and a fixed next step. The model's
 * job is to relay it in the conversation's language and tone; its job is <em>not</em> to decide
 * why a card was declined, because it cannot know and a plausible invention is worse than a
 * blunt code — "your bank declined this" and "you have insufficient funds" call for different
 * actions from the buyer, and a model that guesses between them will sometimes tell someone
 * their account is empty when it is not.
 *
 * <p>An unrecognised reason falls through to a deliberately unhelpful sentence that names the
 * code. That is the honest failure: better an agent that says "the acquirer returned
 * {@code x_y_z} and I do not know what it means" than one that improvises a meaning.
 */
@Component
public class ExplainPaymentOutcomeTool implements AgentTool<ExplainPaymentOutcomeTool.Input> {

    private static final ToolSchema SCHEMA = ToolSchema.builder()
            .string("paymentId", "The payment to explain.", true, 64)
            .build();

    private static final ToolSpec SPEC = new ToolSpec(
            "explain_payment_outcome",
            "Explain what happened to a payment and what the customer can do next. Use the explanation "
                    + "this returns — it reflects what the acquirer actually said. Do not invent a reason for "
                    + "a decline.",
            PolicyOperation.PAYMENT_READ,
            SCHEMA);

    /**
     * The decline vocabulary this service knows how to explain, and the remedy for each.
     *
     * <p>Deliberately a small closed map rather than a heuristic over the code string. A
     * substring match would eventually explain {@code card_expired_retry_ok} as "your card has
     * expired" and be confidently wrong.
     */
    private static final Map<String, Explanation> KNOWN_REASONS = Map.ofEntries(
            Map.entry("insufficient_funds", new Explanation(
                    "The card did not have enough available funds for this amount.",
                    "Try a different card, or a smaller order.")),
            Map.entry("card_declined", new Explanation(
                    "The bank declined the card without giving a more specific reason.",
                    "Try a different card, or contact the bank — only they can say why.")),
            Map.entry("expired_card", new Explanation(
                    "The card has passed its expiry date.",
                    "Use a card that has not expired.")),
            Map.entry("card_expired", new Explanation(
                    "The card has passed its expiry date.",
                    "Use a card that has not expired.")),
            Map.entry("incorrect_cvc", new Explanation(
                    "The security code did not match the card.",
                    "Check the code on the back of the card and try again.")),
            Map.entry("processing_error", new Explanation(
                    "The acquirer had a problem processing the payment. Nothing was charged.",
                    "Try again in a moment.")),
            Map.entry("authentication_required", new Explanation(
                    "The bank wants the cardholder to confirm this payment directly.",
                    "Complete the confirmation the bank asks for, then try again.")),
            Map.entry("razorpay_payment_not_collected", new Explanation(
                    "The order was created with the provider, but no cardholder authorization was "
                            + "collected against it, so there is nothing to charge.",
                    "Complete the provider's own checkout to authorize the payment.")));

    private static final Explanation UNKNOWN = new Explanation(
            "The acquirer returned a reason this service does not have an explanation for.",
            "Quote the reason code to the merchant; only they or the acquirer can interpret it.");

    private final PaymentFlowClient platform;

    public ExplainPaymentOutcomeTool(PaymentFlowClient platform) {
        this.platform = platform;
    }

    public record Input(UUID paymentId) {
    }

    private record Explanation(String meaning, String nextStep) {
    }

    /**
     * @param succeeded whether the payment reached a state where money actually moved. Stated
     *                  explicitly so the agent is not left inferring it from a status string
     */
    public record Result(
            String paymentId,
            String status,
            boolean succeeded,
            String failureReason,
            String explanation,
            String nextStep,
            long amountMinor,
            long capturedAmountMinor,
            long refundedAmountMinor,
            String currency) {
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
                "Explain the outcome of payment %s.".formatted(input.paymentId()));
    }

    @Override
    public ToolResult execute(ToolContext context, Input input, ResolvedAction resolved) {
        PaymentView payment = platform.getPayment(context.correlationId(), input.paymentId()).body();
        Explanation explanation = explain(payment);

        return ToolResult.ok(SPEC.name(), new Result(
                payment.id(),
                payment.status(),
                !payment.isFailed(),
                payment.failureReason(),
                explanation.meaning(),
                explanation.nextStep(),
                payment.amountMinor(),
                payment.capturedAmountMinor(),
                payment.refundedAmountMinor(),
                payment.currency()));
    }

    private static Explanation explain(PaymentView payment) {
        if (!payment.isFailed()) {
            return new Explanation(
                    "The payment is %s. Nothing went wrong.".formatted(payment.status()),
                    "No action is needed.");
        }
        String reason = payment.failureReason();
        if (reason == null || reason.isBlank()) {
            return UNKNOWN;
        }
        return KNOWN_REASONS.getOrDefault(reason.toLowerCase(Locale.ROOT), UNKNOWN);
    }
}
