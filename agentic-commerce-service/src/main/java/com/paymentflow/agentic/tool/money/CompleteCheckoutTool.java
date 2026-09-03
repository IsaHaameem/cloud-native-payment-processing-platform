package com.paymentflow.agentic.tool.money;

import com.paymentflow.agentic.action.AgentActionJournal;
import com.paymentflow.agentic.checkout.Checkout;
import com.paymentflow.agentic.checkout.CheckoutService;
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
 * {@code complete_checkout} — the composite money tool. Create, authorize, capture, run
 * deterministically here rather than by the model.
 *
 * <h2>Where the amount comes from</h2>
 *
 * <p><b>The schema has no amount field, and {@link #resolve} reads the total off the checkout's
 * own row.</b> The model names a checkout; this tool locks that checkout — which freezes its
 * contents and its total — and hands the frozen number to the policy engine. Between the lock
 * and the payment there is no point at which a different figure could be substituted, and the
 * database's own {@code chk_checkouts_total_is_derived} makes a total that disagrees with its
 * line items unrepresentable in the first place.
 *
 * <h2>Why one tool and not three</h2>
 *
 * <p>AD-12 folded authorize and capture into this tool. It halves the model's money surface
 * and costs nothing in demonstrability: each platform call is still its own
 * {@code agent_action_step} row with its own derived idempotency key, its own FSM transition,
 * its own ledger posting and its own event. Separate gating for capture would add nothing,
 * because capture is for the same amount as the authorize it follows and the policy decision
 * already covered that amount.
 *
 * <h2>Retry is replay</h2>
 *
 * <p>Each of the three calls derives its key from
 * {@code (conversationId, tool, checkoutId, step)}. A model that calls this tool twice produces
 * the same three keys, so the second run meets the platform's replay records and returns what
 * the first run did. <b>A failed step is never retried under a new key</b> — that is the one
 * move that could turn a decline into a double charge, and there is no code path here that
 * makes it.
 */
@Component
public class CompleteCheckoutTool implements AgentTool<CompleteCheckoutTool.Input> {

    private static final Logger log = LoggerFactory.getLogger(CompleteCheckoutTool.class);

    static final String TOOL_NAME = "complete_checkout";

    /** The three platform operations, and the discriminators their idempotency keys are derived with. */
    static final String STEP_CREATE = "create_payment";
    static final String STEP_AUTHORIZE = "authorize_payment";
    static final String STEP_CAPTURE = "capture_payment";

    private static final ToolSchema SCHEMA = ToolSchema.builder()
            .string("checkoutId", "The checkout to pay, as returned by create_checkout.", true, 64)
            .string("instrumentToken", "The payment instrument the customer chose. Must be one the "
                    + "merchant offered — you cannot invent, guess or substitute one.", true, 64)
            .build();

    private static final ToolSpec SPEC = new ToolSpec(
            TOOL_NAME,
            "Pay a checkout with the instrument the customer selected. The amount is taken from the "
                    + "checkout itself and cannot be supplied or changed. Returns the payment with its final "
                    + "status; if it fails, the result carries the reason the acquirer gave — report that "
                    + "reason rather than guessing at one.",
            PolicyOperation.CHECKOUT_PAY,
            SCHEMA);

    private final CheckoutService checkoutService;
    private final PaymentFlowClient platform;
    private final InstrumentAllowList instruments;
    private final AgentActionJournal journal;

    public CompleteCheckoutTool(CheckoutService checkoutService, PaymentFlowClient platform,
                                InstrumentAllowList instruments, AgentActionJournal journal) {
        this.checkoutService = checkoutService;
        this.platform = platform;
        this.instruments = instruments;
        this.journal = journal;
    }

    public record Input(UUID checkoutId, String instrumentToken) {
    }

    /** What the agent is told, and the only basis on which it may describe the outcome. */
    public record Result(
            String paymentId,
            String status,
            long amountMinor,
            long capturedAmountMinor,
            String currency,
            String checkoutId,
            String failureReason) {
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public Input validate(ToolArguments arguments) {
        arguments.requireOnly(SCHEMA);
        UUID checkoutId = arguments.requireUuid("checkoutId");
        String instrumentToken = arguments.requireString("instrumentToken", 64);

        // Checked at validation, before anything is locked or read: an instrument the model
        // composed is a rejected call, not a payment attempt that happens to fail.
        if (!instruments.isPermitted(instrumentToken)) {
            throw new AgenticException(AgenticErrorCode.TOOL_ARGUMENTS_INVALID,
                    "That payment instrument is not one the merchant offers. Use an instrument the "
                            + "customer selected rather than naming one yourself.");
        }
        return new Input(checkoutId, instrumentToken);
    }

    /**
     * Locks the checkout and reads its total.
     *
     * <p>This is the deliberate exception to "resolve has no side effects", and the reason is
     * that the lock <em>is</em> the freeze: it is what stops the basket changing underneath a
     * payment already being evaluated, and what stops one quote being paid twice concurrently.
     * The orchestrator releases it when the action does not proceed, so a refusal or a decline
     * leaves the customer's basket intact rather than destroyed.
     */
    @Override
    public ResolvedAction resolve(ToolContext context, Input input) {
        Checkout checkout = checkoutService.lockForPayment(context.merchantId(), context.mode(),
                input.checkoutId());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("checkoutId", checkout.getId().toString());
        summary.put("instrumentToken", input.instrumentToken());
        summary.put("amountMinor", checkout.getTotalMinor());
        summary.put("currency", checkout.getCurrency());

        PolicyRequest.Target target = PolicyRequest.Target.ofCheckout(
                checkout.getId(), checkout.getStatus(), checkout.getTotalMinor(), checkout.getCurrency());

        return new ResolvedAction(target, summary,
                "Charge %d %s for checkout %s.".formatted(checkout.getTotalMinor(), checkout.getCurrency(),
                        checkout.getId()));
    }

    @Override
    public ToolResult execute(ToolContext context, Input input, ResolvedAction resolved) {
        UUID actionId = context.requireAgentActionId();
        UUID checkoutId = input.checkoutId();
        long amountMinor = resolved.target().amountMinor();
        String currency = resolved.target().currency();

        try {
            PaymentView payment = createPayment(context, actionId, input, amountMinor, currency);
            UUID paymentId = UUID.fromString(payment.id());

            payment = authorize(context, actionId, checkoutId, paymentId);
            if (payment.isFailed()) {
                // A decline is an outcome, not an error. The basket survives so the customer can
                // try a different instrument, and the acquirer's own reason is handed back for
                // the agent to report verbatim.
                checkoutService.releaseLock(checkoutId);
                return ToolResult.failure(TOOL_NAME, "payment_declined",
                        "The payment was declined: " + payment.failureReason(),
                        toResult(payment, checkoutId));
            }

            payment = capture(context, actionId, checkoutId, paymentId);
            checkoutService.markPaid(context.merchantId(), context.mode(), checkoutId, paymentId, null);

            return ToolResult.ok(TOOL_NAME, toResult(payment, checkoutId));

        } catch (PaymentFlowClientException e) {
            // The platform's verdict on this request. Reported with its own code, never retried
            // under a fresh key, and the basket is released so the customer can try again.
            checkoutService.releaseLock(checkoutId);
            log.info("complete_checkout refused by the platform checkout={} code={} request_id={}",
                    checkoutId, e.platformCode(), e.requestId());
            return ToolResult.failure(TOOL_NAME, e.platformCode(), e.getMessage());

        } catch (RuntimeException e) {
            // Includes the platform being unreachable. The lock is released, the step rows say
            // what was attempted and under which key, and re-running the tool re-derives those
            // same keys — so the platform, not this service, decides whether anything is
            // repeated.
            checkoutService.releaseLock(checkoutId);
            throw e;
        }
    }

    // ── The three platform steps ────────────────────────────────────────────────────────

    private PaymentView createPayment(ToolContext context, UUID actionId, Input input, long amountMinor,
                                      String currency) {
        String key = IdempotencyKeys.forStep(context.conversationId(), TOOL_NAME, input.checkoutId(),
                STEP_CREATE);
        UUID stepId = journal.beginStep(actionId, STEP_CREATE, key, context.correlationId());
        try {
            PlatformResponse<PaymentView> response = platform.createPayment(
                    context.correlationId(), key, amountMinor, currency,
                    "Agent checkout " + input.checkoutId(), input.instrumentToken(),
                    metadata(context, input.checkoutId()));
            PaymentView payment = response.body();
            journal.stepSucceeded(stepId, response.httpStatus(), response.requestId(),
                    UUID.fromString(payment.id()), null);
            return payment;
        } catch (RuntimeException e) {
            failStep(stepId, e);
            throw e;
        }
    }

    private PaymentView authorize(ToolContext context, UUID actionId, UUID checkoutId, UUID paymentId) {
        String key = IdempotencyKeys.forStep(context.conversationId(), TOOL_NAME, checkoutId, STEP_AUTHORIZE);
        UUID stepId = journal.beginStep(actionId, STEP_AUTHORIZE, key, context.correlationId());
        try {
            PlatformResponse<PaymentView> response =
                    platform.authorizePayment(context.correlationId(), key, paymentId);
            journal.stepSucceeded(stepId, response.httpStatus(), response.requestId(), paymentId, null);
            return response.body();
        } catch (RuntimeException e) {
            failStep(stepId, e);
            throw e;
        }
    }

    private PaymentView capture(ToolContext context, UUID actionId, UUID checkoutId, UUID paymentId) {
        String key = IdempotencyKeys.forStep(context.conversationId(), TOOL_NAME, checkoutId, STEP_CAPTURE);
        UUID stepId = journal.beginStep(actionId, STEP_CAPTURE, key, context.correlationId());
        try {
            PlatformResponse<PaymentView> response =
                    platform.capturePayment(context.correlationId(), key, paymentId);
            journal.stepSucceeded(stepId, response.httpStatus(), response.requestId(), paymentId, null);
            return response.body();
        } catch (RuntimeException e) {
            failStep(stepId, e);
            throw e;
        }
    }

    private void failStep(UUID stepId, RuntimeException e) {
        if (e instanceof PaymentFlowClientException failure) {
            journal.stepFailed(stepId, failure.httpStatus(), failure.requestId(), failure.platformCode(),
                    failure.getMessage());
        } else {
            journal.stepFailed(stepId, null, null, "platform_unavailable", e.getMessage());
        }
    }

    /**
     * What the payment carries back to the agentic side.
     *
     * <p>Ids only. Metadata is returned on every read of the payment and is filterable, which
     * makes it the join that lets an auditor start at a payment in the platform and arrive at
     * the conversation that caused it — but it is merchant-visible free text, so nothing goes in
     * it that is not already an identifier.
     */
    private static Map<String, String> metadata(ToolContext context, UUID checkoutId) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("agent_conversation_id", context.conversationId().toString());
        metadata.put("agent_checkout_id", checkoutId.toString());
        if (context.agentActionId() != null) {
            metadata.put("agent_action_id", context.agentActionId().toString());
        }
        return metadata;
    }

    private static Result toResult(PaymentView payment, UUID checkoutId) {
        return new Result(payment.id(), payment.status(), payment.amountMinor(),
                payment.capturedAmountMinor(), payment.currency(), checkoutId.toString(),
                payment.failureReason());
    }
}
