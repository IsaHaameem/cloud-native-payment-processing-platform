package com.paymentflow.agentic.tool.money;

import com.paymentflow.agentic.action.AgentActionJournal;
import com.paymentflow.agentic.error.AgenticErrorCode;
import com.paymentflow.agentic.error.AgenticException;
import com.paymentflow.agentic.idempotency.IdempotencyKeys;
import com.paymentflow.agentic.platform.PaymentFlowClient;
import com.paymentflow.agentic.platform.PaymentFlowClientException;
import com.paymentflow.agentic.platform.PaymentView;
import com.paymentflow.agentic.platform.PlatformResponse;
import com.paymentflow.agentic.policy.PolicyOperation;
import com.paymentflow.agentic.policy.PolicyRequest;
import com.paymentflow.agentic.tool.AgentTool;
import com.paymentflow.agentic.tool.ResolvedAction;
import com.paymentflow.agentic.tool.ToolArguments;
import com.paymentflow.agentic.tool.ToolContext;
import com.paymentflow.agentic.tool.ToolResult;
import com.paymentflow.agentic.tool.ToolSchema;
import com.paymentflow.agentic.tool.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * {@code request_refund} — the tool the approval gate exists for.
 *
 * <p>This is the one tool where the model may name an amount, and it is worth being precise
 * about why that is safe. The amount is a <em>request</em>, not an instruction:
 *
 * <ol>
 *   <li>{@link #resolve} reads the payment from the platform and <b>rejects</b> a request for
 *       more than is actually refundable ({@code captured − refunded}). It does not quietly
 *       reduce it: silently substituting a different number would make the executed action
 *       differ from the proposed one, and the trail would then record a refund nobody asked
 *       for. Omitting the amount means "whatever remains", and resolves to the payment's own
 *       figure.</li>
 *   <li>The policy engine then bounds the resolved figure by the per-refund cap and the
 *       conversation's refund budget, and requires human approval above the threshold.</li>
 *   <li>The approval binds to the resolved figure, so an amount that moved between approval and
 *       execution stops the execution.</li>
 *   <li>The platform's own {@code Payment.refund()} refuses an over-refund before any row is
 *       written, whatever the three checks above concluded.</li>
 * </ol>
 *
 * <p>Four independent bounds, of which only the first is in this class. The model's number
 * survives all four only by already being correct.
 *
 * <p>Omitting the amount refunds everything remaining — which is the common case and the one a
 * model gets right most often, so it is the default rather than something it has to compute.
 */
@Component
public class RequestRefundTool implements AgentTool<RequestRefundTool.Input> {

    private static final Logger log = LoggerFactory.getLogger(RequestRefundTool.class);

    static final String TOOL_NAME = "request_refund";
    static final String STEP_REFUND = "refund_payment";

    /** Matches the platform's own {@code reason} bound, so an over-long reason fails here with a clear message. */
    private static final int MAX_REASON_LENGTH = 500;

    /** A refund is bounded far below this by policy; the schema bound only keeps the number sane. */
    private static final long MAX_DECLARED_AMOUNT = 1_000_000_000L;

    private static final ToolSchema SCHEMA = ToolSchema.builder()
            .string("paymentId", "The payment to refund.", true, 64)
            .integer("amountMinor", "How much to refund, in the currency's minor unit. Omit to refund "
                    + "everything still refundable. Cannot exceed what the payment actually holds.",
                    false, 1, MAX_DECLARED_AMOUNT)
            .string("reason", "Why the refund is being issued, for the merchant's records.", false,
                    MAX_REASON_LENGTH)
            .build();

    private static final ToolSpec SPEC = new ToolSpec(
            TOOL_NAME,
            "Refund a payment, in whole or in part. Large refunds require a human to approve them before "
                    + "anything happens — if the result says approval is required, tell the customer that "
                    + "plainly and do not retry. The refundable amount is decided by the payment, not by you.",
            PolicyOperation.REFUND_CREATE,
            SCHEMA);

    private final PaymentFlowClient platform;
    private final AgentActionJournal journal;

    public RequestRefundTool(PaymentFlowClient platform, AgentActionJournal journal) {
        this.platform = platform;
        this.journal = journal;
    }

    public record Input(UUID paymentId, Long requestedAmountMinor, String reason) {
    }

    public record Result(
            String paymentId,
            String status,
            long refundedAmountMinor,
            long capturedAmountMinor,
            String currency,
            String failureReason) {
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public Input validate(ToolArguments arguments) {
        arguments.requireOnly(SCHEMA);
        return new Input(
                arguments.requireUuid("paymentId"),
                arguments.optionalLong("amountMinor", 1, MAX_DECLARED_AMOUNT),
                arguments.optionalString("reason", MAX_REASON_LENGTH));
    }

    /**
     * Reads the payment and decides the real amount.
     *
     * <p>A read, not a mutation — nothing is refunded here, and the payment is fetched rather
     * than remembered so that the figure the policy engine bounds is the platform's current one
     * rather than one this service cached.
     */
    @Override
    public ResolvedAction resolve(ToolContext context, Input input) {
        PaymentView payment = platform.getPayment(context.correlationId(), input.paymentId()).body();

        long refundable = payment.refundableMinor();
        if (refundable <= 0) {
            throw new AgenticException(AgenticErrorCode.TOOL_ARGUMENTS_INVALID,
                    "Payment %s has nothing left to refund.".formatted(input.paymentId()));
        }
        // REJECTED, not clamped. An earlier draft of this tool quietly reduced an over-large
        // request to the refundable figure and carried on, which is the wrong behaviour for a
        // specific reason: it makes the action that executes different from the action that was
        // proposed, so the audit trail records a refund nobody asked for and the approver — if
        // one is involved — reviews a number the model never named. Phase 11 requires a
        // conflicting amount to be an auditable rejection instead.
        //
        // Omitting the amount is not a conflict. It is an explicit "refund what remains", and
        // resolving it from the payment is the whole point of allowing it.
        if (input.requestedAmountMinor() != null && input.requestedAmountMinor() > refundable) {
            throw new AgenticException(AgenticErrorCode.TOOL_ARGUMENTS_INVALID,
                    ("The requested refund of %d does not match what payment %s can refund (%d remaining). "
                            + "Ask for an amount within that, or omit the amount to refund the remainder.")
                            .formatted(input.requestedAmountMinor(), input.paymentId(), refundable));
        }
        long amountMinor = input.requestedAmountMinor() == null
                ? refundable
                : input.requestedAmountMinor();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("paymentId", input.paymentId().toString());
        summary.put("requestedAmountMinor", input.requestedAmountMinor());
        summary.put("amountMinor", amountMinor);
        summary.put("currency", payment.currency());
        summary.put("reason", input.reason());

        PolicyRequest.Target target =
                PolicyRequest.Target.ofPayment(input.paymentId(), amountMinor, payment.currency());

        return new ResolvedAction(target, summary,
                "Refund %d %s of payment %s (%d refundable).".formatted(amountMinor, payment.currency(),
                        input.paymentId(), refundable));
    }

    @Override
    public ToolResult execute(ToolContext context, Input input, ResolvedAction resolved) {
        UUID actionId = context.requireAgentActionId();
        long amountMinor = resolved.target().amountMinor();

        // Per AD-12 the fourth component is the amount, not a step name. Two refunds of the same
        // amount against the same payment in one conversation therefore derive one key, and the
        // second is a replay rather than a second refund — the safe direction for a model that
        // repeats itself.
        String key = IdempotencyKeys.forRefund(context.conversationId(), TOOL_NAME, input.paymentId(),
                amountMinor);
        UUID stepId = journal.beginStep(actionId, STEP_REFUND, key, context.correlationId());

        try {
            PlatformResponse<PaymentView> response = platform.refundPayment(
                    context.correlationId(), key, input.paymentId(), amountMinor, input.reason());
            PaymentView payment = response.body();
            journal.stepSucceeded(stepId, response.httpStatus(), response.requestId(), input.paymentId(),
                    null);
            return ToolResult.ok(TOOL_NAME, toResult(payment));

        } catch (PaymentFlowClientException e) {
            journal.stepFailed(stepId, e.httpStatus(), e.requestId(), e.platformCode(), e.getMessage());
            log.info("request_refund refused by the platform payment={} code={} request_id={}",
                    input.paymentId(), e.platformCode(), e.requestId());
            return ToolResult.failure(TOOL_NAME, e.platformCode(), e.getMessage());

        } catch (RuntimeException e) {
            journal.stepFailed(stepId, null, null, "platform_unavailable", e.getMessage());
            throw e;
        }
    }

    private static Result toResult(PaymentView payment) {
        return new Result(payment.id(), payment.status(), payment.refundedAmountMinor(),
                payment.capturedAmountMinor(), payment.currency(), payment.failureReason());
    }
}
