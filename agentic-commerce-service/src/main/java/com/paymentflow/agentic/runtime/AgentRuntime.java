package com.paymentflow.agentic.runtime;

import com.paymentflow.agentic.action.AgentAction;
import com.paymentflow.agentic.action.AgentActionJournal;
import com.paymentflow.agentic.action.Redactor;
import com.paymentflow.agentic.approval.Approval;
import com.paymentflow.agentic.approval.ApprovalBinding;
import com.paymentflow.agentic.approval.ApprovalService;
import com.paymentflow.agentic.checkout.CheckoutService;
import com.paymentflow.agentic.config.AgenticProperties;
import com.paymentflow.agentic.conversation.Conversation;
import com.paymentflow.agentic.conversation.ConversationMessage;
import com.paymentflow.agentic.conversation.ConversationService;
import com.paymentflow.agentic.error.AgenticException;
import com.paymentflow.agentic.llm.LlmClient;
import com.paymentflow.agentic.llm.LlmMessage;
import com.paymentflow.agentic.llm.LlmRequest;
import com.paymentflow.agentic.llm.LlmResponse;
import com.paymentflow.agentic.llm.LlmToolCall;
import com.paymentflow.agentic.llm.LlmToolResult;
import com.paymentflow.agentic.llm.LlmUnavailableException;
import com.paymentflow.agentic.llm.MalformedLlmOutputException;
import com.paymentflow.agentic.observability.AgentMetrics;
import com.paymentflow.agentic.policy.PolicyDecisionLog;
import com.paymentflow.agentic.policy.PolicyEngine;
import com.paymentflow.agentic.policy.PolicyOperation;
import com.paymentflow.agentic.policy.PolicyRequest;
import com.paymentflow.agentic.policy.PolicyVerdict;
import com.paymentflow.agentic.tool.ResolvedAction;
import com.paymentflow.agentic.tool.ToolContext;
import com.paymentflow.agentic.tool.ToolRegistry;
import com.paymentflow.agentic.tool.ToolResult;
import com.paymentflow.agentic.tool.ToolSpec;
import com.paymentflow.agentic.tool.ValidatedToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The agent loop: an explicit, bounded state machine over components that already exist.
 *
 * <pre>
 *   user message
 *       ↓
 *   LLM proposal ─────────────────────────────────► final response   (no tool calls)
 *       ↓
 *   validate against ToolRegistry ────────────────► rejected         (unknown tool / bad schema)
 *       ↓
 *   resolve server-side facts ────────────────────► rejected         (amount conflict, 404, expired)
 *       ↓
 *   PolicyEngine ─────────────────────────────────► refused
 *       ↓                     ↓
 *       │              requires approval ──────────► turn STOPS, nothing financial ran
 *       ↓
 *   execute tool → structured result
 *       ↓
 *   LLM continuation … (bounded) ─────────────────► final response
 * </pre>
 *
 * <h2>What this class does not do</h2>
 *
 * <p>It contains <b>no payment business rule</b>. It does not decide an amount, a threshold, an
 * approval outcome, an idempotency key or a refund bound — every one of those belongs to a
 * component that already owns it, and this class calls them in order. Its whole job is
 * sequencing, bounding, and recording. If a financial rule ever appears in this file, it is in
 * the wrong place.
 *
 * <h2>Three things the model cannot do from here</h2>
 *
 * <ol>
 *   <li><b>It cannot reach a tool it was not given.</b> Execution goes through
 *       {@link ToolRegistry#validate}, which resolves by exact registered name. There is no
 *       reflection, no name-to-class mapping, and no switch on a tool name anywhere in the
 *       pipeline.</li>
 *   <li><b>It cannot supply a financial fact.</b> The amount, currency, checkout state and
 *       payment status all come from {@code resolve}, which reads them from this service's own
 *       tables or from the platform. {@link #rejectAmountConflict} additionally refuses any call
 *       whose arguments name an amount that disagrees with the resolved one — a conflict is an
 *       auditable rejection, never a silent correction.</li>
 *   <li><b>It cannot approve anything by saying so.</b> {@code REQUIRES_APPROVAL} ends the turn.
 *       The only way past it is {@link #executeApprovedAction}, reached from an authenticated
 *       API call, which redeems a stored approval against freshly re-resolved facts.</li>
 * </ol>
 *
 * <h2>Bounds</h2>
 *
 * <p>Three, all configuration-driven, none of them optional: iterations per turn
 * ({@code max-tool-iterations}), tool calls per conversation (enforced inside
 * {@code PolicyEngine} from {@code max-tool-calls-per-conversation}), and wall-clock time per
 * turn ({@code max-turn-duration-ms}). Reaching any of them terminates the turn with a
 * structured result rather than an exception, because a bound being reached is the system
 * working.
 */
@Service
public class AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);

    /** Recorded as the category on an action whose tool was never recognised. */
    private static final String CATEGORY_UNKNOWN = "UNKNOWN";

    /** {@code agent_actions.tool_name} is varchar(64), and the model supplies this string. */
    private static final int MAX_RECORDED_TOOL_NAME = 64;

    /**
     * Argument names that would carry an amount. Any of them appearing in a tool call is checked
     * against the resolved figure — see {@link #rejectAmountConflict}.
     */
    private static final List<String> AMOUNT_ARGUMENT_NAMES =
            List.of("amountminor", "amount", "totalminor", "total", "priceminor", "price");

    private final LlmClient llmClient;
    private final SystemPrompt systemPrompt;
    private final ToolRegistry toolRegistry;
    private final PolicyEngine policyEngine;
    private final PolicyDecisionLog policyDecisionLog;
    private final ApprovalService approvalService;
    private final AgentActionJournal journal;
    private final ConversationService conversations;
    private final CheckoutService checkoutService;
    private final AgenticProperties properties;
    private final ObjectMapper objectMapper;
    private final AgentMetrics metrics;
    private final Clock clock;

    public AgentRuntime(LlmClient llmClient, SystemPrompt systemPrompt, ToolRegistry toolRegistry,
                        PolicyEngine policyEngine, PolicyDecisionLog policyDecisionLog,
                        ApprovalService approvalService, AgentActionJournal journal,
                        ConversationService conversations, CheckoutService checkoutService,
                        AgenticProperties properties, ObjectMapper objectMapper, AgentMetrics metrics,
                        Clock clock) {
        this.llmClient = llmClient;
        this.systemPrompt = systemPrompt;
        this.toolRegistry = toolRegistry;
        this.policyEngine = policyEngine;
        this.policyDecisionLog = policyDecisionLog;
        this.approvalService = approvalService;
        this.journal = journal;
        this.conversations = conversations;
        this.checkoutService = checkoutService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Who the turn is being run for. Established from the caller's session, never from the model. */
    public record Caller(UUID merchantId, String mode, String sessionRef, String principal) {
    }

    // ── The turn ────────────────────────────────────────────────────────────────────────

    /**
     * Runs one turn: the customer says something, the agent acts and replies.
     *
     * <p>Deliberately not {@code @Transactional}. A turn makes network calls to a model and to
     * the payment platform, and holding a database transaction across those would pin a
     * connection for the length of an LLM response. Every write inside is its own transaction,
     * committed as it happens — which is also what makes the action trail survive a failure
     * mid-turn.
     */
    public AgentTurnResult handleUserMessage(Caller caller, UUID conversationId, String userMessage) {
        Conversation conversation = conversations.requireActive(caller.merchantId(), caller.mode(),
                conversationId);
        conversations.append(conversationId, ConversationMessage.Role.USER, userMessage);

        AgenticProperties.Llm llm = properties.llm();
        Instant deadline = llm.hasTurnDeadline()
                ? clock.instant().plus(Duration.ofMillis(llm.maxTurnDurationMs()))
                : Instant.MAX;

        List<AgentTurnResult.ActionSummary> actions = new ArrayList<>();
        List<LlmMessage> messages = new ArrayList<>(toLlmMessages(conversations.promptWindow(conversationId)));

        for (int iteration = 0; iteration < llm.maxToolIterations(); iteration++) {
            if (clock.instant().isAfter(deadline)) {
                return limitReached(conversationId, actions,
                        "I ran out of time on that one. Nothing further was attempted.");
            }

            LlmResponse response;
            Instant startedAt = clock.instant();
            try {
                response = llmClient.complete(buildRequest(messages));
                metrics.llmCall(llmClient.providerName(), "ok",
                        Duration.between(startedAt, clock.instant()));
            } catch (MalformedLlmOutputException e) {
                metrics.llmCall(llmClient.providerName(), "malformed_output",
                        Duration.between(startedAt, clock.instant()));
                // Recorded and stopped. Nothing is parsed out of a malformed response, and in
                // particular nothing financial is reconstructed from it.
                log.warn("The language model returned unreadable output; no tool was executed.", e);
                return stopped(conversationId, actions, AgentTurnResult.AgentStopReason.LLM_OUTPUT_INVALID,
                        "I could not understand the assistant's own response, so I have not done anything. "
                                + "Please try again.");
            } catch (LlmUnavailableException e) {
                metrics.llmCall(llmClient.providerName(), "unavailable",
                        Duration.between(startedAt, clock.instant()));
                log.warn("The language model was unavailable during a turn.", e);
                return stopped(conversationId, actions, AgentTurnResult.AgentStopReason.LLM_UNAVAILABLE,
                        "I could not reach the assistant just now. Nothing has been charged or changed. "
                                + "Please try again in a moment.");
            }

            messages.add(LlmMessage.assistant(response.text(), response.toolCalls()));
            if (response.text() != null && !response.text().isBlank()) {
                conversations.append(conversationId, ConversationMessage.Role.ASSISTANT, response.text());
            }

            if (!response.requestsTools()) {
                metrics.turnCompleted(AgentTurnResult.AgentStopReason.COMPLETED.name());
                // Includes MAX_TOKENS and OTHER: a truncated or unrecognised stop is treated as
                // "no tools", never as an invitation to guess what the model meant to call.
                return AgentTurnResult.completed(conversationId, replyOf(response), actions);
            }

            List<LlmToolResult> results = new ArrayList<>();
            for (LlmToolCall call : response.toolCalls()) {
                ToolOutcome outcome = runToolCall(caller, conversation, call);
                actions.add(outcome.summary());
                results.add(outcome.result());

                if (outcome.approvalId() != null) {
                    // The gate. The financial operation did not run, and the turn stops here
                    // rather than handing the model another chance to talk about it.
                    conversations.append(conversationId, ConversationMessage.Role.TOOL,
                            outcome.result().content());
                    metrics.turnCompleted(AgentTurnResult.AgentStopReason.APPROVAL_REQUIRED.name());
                    return AgentTurnResult.approvalRequired(conversationId,
                            "That needs to be approved by the merchant before it can go ahead. "
                                    + "Nothing has been charged or refunded yet.",
                            actions, outcome.approvalId());
                }
            }
            messages.add(LlmMessage.toolResults(results));
            results.forEach(result -> conversations.append(conversationId,
                    ConversationMessage.Role.TOOL, result.content()));
        }

        return limitReached(conversationId, actions,
                "I have done as much as I can in one go. Tell me what you would like to do next.");
    }

    // ── One tool call ───────────────────────────────────────────────────────────────────

    /** One tool call's outcome: what the model is told, what the trail records, and any approval opened. */
    private record ToolOutcome(LlmToolResult result, AgentTurnResult.ActionSummary summary, UUID approvalId) {
    }

    /**
     * The five stages, in order, for one proposed call. Every exit records why.
     *
     * <p>The tool-call counter is incremented first, before anything can reject the call. An
     * agent looping on rejections is precisely what the ceiling exists to stop, and a counter
     * that only advanced on valid calls would never reach it.
     */
    private ToolOutcome runToolCall(Caller caller, Conversation conversation, LlmToolCall call) {
        conversations.recordToolCall(conversation.getId());
        String correlationId = UUID.randomUUID().toString();
        String toolName = truncate(call.name(), MAX_RECORDED_TOOL_NAME);

        // ── 1. Validate against the registry ────────────────────────────────────────────
        ValidatedToolCall validated;
        try {
            validated = toolRegistry.validate(call.name(), call.arguments());
        } catch (AgenticException e) {
            // Recorded even though it never became a real action. An unknown tool or a malformed
            // argument list is exactly the kind of attempt a reviewer wants to be able to see.
            AgentAction action = journal.propose(caller.merchantId(), caller.mode(), conversation.getId(),
                    correlationId, toolName, CATEGORY_UNKNOWN, Redactor.summarise(call.arguments()),
                    modelName(), systemPrompt.version());
            journal.failed(action.getId(), e.errorCode().code(), e.getMessage());
            metrics.toolValidationFailure(call.name(), e.errorCode().code());
            return rejected(call, action.getId(), toolName, e.errorCode().code(), e.getMessage());
        }

        ToolSpec spec = validated.spec();
        AgentAction action = journal.propose(caller.merchantId(), caller.mode(), conversation.getId(),
                correlationId, spec.name(), spec.category().name(),
                Redactor.summarise(validated.arguments()), modelName(), systemPrompt.version());
        UUID actionId = action.getId();
        ToolContext context = new ToolContext(caller.merchantId(), caller.mode(), conversation.getId(),
                caller.sessionRef(), caller.principal(), correlationId, actionId);

        // ── 2. Resolve server-side facts ────────────────────────────────────────────────
        ResolvedAction resolved;
        try {
            resolved = validated.resolve(context);
            rejectAmountConflict(spec, validated.arguments(), resolved);
        } catch (AgenticException e) {
            releaseAnyLock(caller, spec, null);
            journal.failed(actionId, e.errorCode().code(), e.getMessage());
            return rejected(call, actionId, spec.name(), e.errorCode().code(), e.getMessage());
        }
        journal.validated(actionId, resolved.inputSummary(), resolved.target().checkoutId(),
                resolved.target().paymentId());

        // ── 3. Policy, persisted before anything financial happens ──────────────────────
        // The conversation is re-read so the budget check sees the counters as they stand now,
        // not as they stood when the turn began.
        Conversation current = conversations.require(caller.merchantId(), caller.mode(), conversation.getId());
        PolicyRequest policyRequest = new PolicyRequest(context.toPolicyActor(),
                current.toPolicyConversation(), spec.name(), spec.operation(), resolved.target());
        PolicyVerdict verdict = policyEngine.evaluate(policyRequest);
        policyDecisionLog.record(actionId, policyRequest, verdict);
        metrics.policyDecision(verdict.decision().name(), verdict.ruleId(), spec.name());

        if (verdict.decision() == com.paymentflow.agentic.policy.PolicyDecision.REFUSE) {
            releaseAnyLock(caller, spec, resolved);
            journal.refused(actionId, verdict.decision(), verdict.reasonCode(), verdict.reason(),
                    verdict.budgetRemainingMinor());
            // Reported as REFUSED with the decision on it, not as a generic failure. A refusal
            // and a malformed argument list are both "the tool did not run", but only one of
            // them is the policy engine doing its job, and an audit view that could not tell
            // them apart would be unable to show the gate working.
            return new ToolOutcome(
                    LlmToolResult.failure(call.id(), spec.name(), render(mapOf(
                            "ok", false, "errorCode", verdict.reasonCode(),
                            "message", verdict.reason()))),
                    new AgentTurnResult.ActionSummary(actionId, spec.name(), "REFUSED",
                            verdict.decision().name(), false, verdict.reasonCode(), verdict.reason()),
                    null);
        }

        // ── 4. Approval, when policy says a human decides ───────────────────────────────
        if (verdict.requiresApproval()) {
            Approval approval = approvalService.request(actionId, policyRequest, verdict);
            journal.approvalRequired(actionId, approval.getId(), verdict.reasonCode(), verdict.reason(),
                    verdict.budgetRemainingMinor());
            metrics.approval("required");
            // The lock is released: the basket must stay usable while a person decides, and an
            // approval may sit for its whole TTL.
            releaseAnyLock(caller, spec, resolved);

            AgentTurnResult.ActionSummary summary = new AgentTurnResult.ActionSummary(actionId,
                    spec.name(), "APPROVAL_REQUIRED", verdict.decision().name(), false,
                    verdict.reasonCode(), verdict.reason());
            return new ToolOutcome(
                    LlmToolResult.failure(call.id(), spec.name(), render(Map.of(
                            "ok", false,
                            "errorCode", "approval_required",
                            "message", verdict.reason(),
                            "approvalId", approval.getId().toString()))),
                    summary, approval.getId());
        }

        // ── 5. Execute ──────────────────────────────────────────────────────────────────
        return execute(caller, conversation, validated, call, context, resolved, actionId,
                verdict.budgetRemainingMinor());
    }

    /**
     * Runs the tool and records what came back.
     *
     * <p>{@code EXECUTING} is written and committed before the tool is called, so an action
     * interrupted mid-flight is visible afterwards as one that was attempted.
     */
    private ToolOutcome execute(Caller caller, Conversation conversation, ValidatedToolCall validated,
                                LlmToolCall call, ToolContext context, ResolvedAction resolved,
                                UUID actionId, Long budgetRemaining) {
        ToolSpec spec = validated.spec();
        journal.executing(actionId, budgetRemaining);
        try {
            // The same validated call that was resolved and evaluated. Re-validating here would
            // produce a second typed input that policy never saw — a small gap, but exactly the
            // kind that makes "what executed is what was approved" stop being true.
            ToolResult result = validated.execute(context, resolved);

            metrics.toolCall(spec.name(), result.ok());
            if (spec.movesMoney()) {
                metrics.paymentAction(spec.operation().name(), result.ok() ? "ok" : "failed");
            }

            if (result.ok()) {
                UUID paymentId = paymentIdOf(result);
                journal.executed(actionId, paymentId);
                creditBudget(conversation, spec, resolved);
                return new ToolOutcome(
                        LlmToolResult.ok(call.id(), spec.name(), render(Map.of(
                                "ok", true, "payload", result.payload()))),
                        new AgentTurnResult.ActionSummary(actionId, spec.name(), "EXECUTED", "PERMIT",
                                true, null, null),
                        null);
            }

            journal.failed(actionId, result.errorCode(), result.errorMessage());
            return new ToolOutcome(
                    LlmToolResult.failure(call.id(), spec.name(), render(mapOf(
                            "ok", false,
                            "errorCode", result.errorCode(),
                            "message", result.errorMessage(),
                            "payload", result.payload()))),
                    new AgentTurnResult.ActionSummary(actionId, spec.name(), "FAILED", "PERMIT", false,
                            result.errorCode(), result.errorMessage()),
                    null);

        } catch (AgenticException e) {
            releaseAnyLock(caller, spec, resolved);
            journal.failed(actionId, e.errorCode().code(), e.getMessage());
            return rejected(call, actionId, spec.name(), e.errorCode().code(), e.getMessage());

        } catch (RuntimeException e) {
            // Anything unforeseen. Recorded as a failure with a generic code, and emphatically
            // not reported to the customer as a success — a swallowed exception that becomes
            // "payment complete" is the single worst outcome this class could produce.
            releaseAnyLock(caller, spec, resolved);
            log.error("An agent tool failed unexpectedly: tool={} action={}", spec.name(), actionId, e);
            journal.failed(actionId, "tool_execution_error", e.getMessage());
            return rejected(call, actionId, spec.name(), "tool_execution_error",
                    "The action could not be completed.");
        }
    }

    // ── Approval redemption ─────────────────────────────────────────────────────────────

    /**
     * Executes an action a human approved. <b>The only path past {@code REQUIRES_APPROVAL}.</b>
     *
     * <p>The tool call is rebuilt <em>from the approval's own binding</em>, not from anything
     * remembered about what the model asked for. That is the point: the binding is what the
     * approver agreed to, so reconstructing from it means the thing that executes is the thing
     * that was reviewed. A consequence worth stating — the model's free-text refund reason is
     * not carried across, and the executed refund carries a server-authored one instead.
     *
     * <p>Policy is evaluated a second time, and that second decision is persisted alongside the
     * first. It catches everything that could have changed while a person was deciding: an
     * exhausted budget, a closed conversation, a checkout that expired, a threshold an operator
     * lowered. A {@code REFUSE} here blocks the execution even though an approval exists —
     * approval waives the approval requirement, never a hard cap.
     */
    public AgentTurnResult executeApprovedAction(Caller caller, UUID approvalId) {
        Approval approval = approvalService.require(caller.merchantId(), caller.mode(), approvalId);
        AgentAction action = journal.requireAction(caller.merchantId(), caller.mode(),
                approval.getAgentActionId());
        Conversation conversation = conversations.require(caller.merchantId(), caller.mode(),
                action.getConversationId());

        String correlationId = UUID.randomUUID().toString();
        ToolContext context = new ToolContext(caller.merchantId(), caller.mode(), conversation.getId(),
                caller.sessionRef(), caller.principal(), correlationId, action.getId());

        ValidatedToolCall validated = toolRegistry.validate(action.getToolName(),
                argumentsFromBinding(approval));
        ToolSpec spec = validated.spec();

        ResolvedAction resolved;
        try {
            resolved = validated.resolve(context);
        } catch (AgenticException e) {
            journal.failed(action.getId(), e.errorCode().code(), e.getMessage());
            return stopped(conversation.getId(), List.of(summaryOf(action.getId(), spec.name(), "FAILED",
                            null, e.errorCode().code(), e.getMessage())),
                    AgentTurnResult.AgentStopReason.FAILED,
                    "That could no longer be carried out: " + e.getMessage());
        }

        PolicyRequest policyRequest = new PolicyRequest(context.toPolicyActor(),
                conversation.toPolicyConversation(), spec.name(), spec.operation(), resolved.target());
        PolicyVerdict verdict = policyEngine.evaluate(policyRequest);
        policyDecisionLog.record(action.getId(), policyRequest, verdict);

        if (verdict.decision() == com.paymentflow.agentic.policy.PolicyDecision.REFUSE) {
            releaseAnyLock(caller, spec, resolved);
            journal.refused(action.getId(), verdict.decision(), verdict.reasonCode(), verdict.reason(),
                    verdict.budgetRemainingMinor());
            return stopped(conversation.getId(), List.of(summaryOf(action.getId(), spec.name(), "REFUSED",
                            verdict.decision().name(), verdict.reasonCode(), verdict.reason())),
                    AgentTurnResult.AgentStopReason.FAILED,
                    "That is no longer allowed: " + verdict.reason());
        }

        // Redeemed against the freshly-resolved facts, never against the approval's own copy of
        // them — comparing a value with itself would make this check decorative. Throws if the
        // amount, currency, target, merchant or operation moved, or if the approval has expired
        // or was already spent.
        approvalService.redeem(caller.merchantId(), caller.mode(), approvalId,
                ApprovalBinding.of(policyRequest));

        LlmToolCall syntheticCall = new LlmToolCall("approval_" + approvalId, spec.name(),
                argumentsFromBinding(approval));
        ToolOutcome outcome = execute(caller, conversation, validated, syntheticCall, context, resolved,
                action.getId(), verdict.budgetRemainingMinor());

        conversations.append(conversation.getId(), ConversationMessage.Role.TOOL,
                outcome.result().content());

        return AgentTurnResult.completed(conversation.getId(),
                outcome.summary().ok()
                        ? "The approved action has been carried out."
                        : "The approved action was attempted and did not complete: "
                                + outcome.summary().message(),
                List.of(outcome.summary()));
    }

    /**
     * Rebuilds a tool's arguments from what was actually approved.
     *
     * <p>Only the financially material fields survive, because only those were bound. Anything
     * cosmetic the model originally sent is deliberately dropped rather than replayed from a
     * transcript that no human reviewed.
     */
    private static Map<String, Object> argumentsFromBinding(Approval approval) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (approval.getRequestedOperation() == PolicyOperation.REFUND_CREATE) {
            arguments.put("paymentId", approval.getPaymentId().toString());
            if (approval.getAmountMinor() != null) {
                arguments.put("amountMinor", approval.getAmountMinor());
            }
            arguments.put("reason", "Refund approved by the merchant.");
        } else if (approval.getRequestedOperation() == PolicyOperation.CHECKOUT_PAY) {
            arguments.put("checkoutId", approval.getCheckoutId().toString());
        }
        return arguments;
    }

    // ── Guards ──────────────────────────────────────────────────────────────────────────

    /**
     * Refuses a call whose arguments name an amount that disagrees with the resolved one.
     *
     * <p>Belt and braces over three things that already hold: {@code complete_checkout} declares
     * no amount argument at all, {@code ToolArguments.requireOnly} rejects an undeclared field,
     * and {@code request_refund} rejects an over-large request itself. This catches the case
     * none of them would — a tool added later that accepts an amount and quietly prefers the
     * model's copy of it.
     *
     * <p>Rejection rather than correction, and the reason matters: silently substituting the
     * server's figure would make the action that executed differ from the one that was proposed,
     * and the audit trail would record a charge nobody asked for.
     */
    private static void rejectAmountConflict(ToolSpec spec, Map<String, Object> arguments,
                                             ResolvedAction resolved) {
        Long resolvedAmount = resolved.target().amountMinor();
        if (resolvedAmount == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            if (!AMOUNT_ARGUMENT_NAMES.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                continue;
            }
            Long supplied = asLong(entry.getValue());
            if (supplied != null && !supplied.equals(resolvedAmount)) {
                throw new AgenticException(com.paymentflow.agentic.error.AgenticErrorCode.TOOL_ARGUMENTS_INVALID,
                        ("The amount in the request (%d) does not match the amount the merchant resolved "
                                + "for this action (%d). The action was not carried out.")
                                .formatted(supplied, resolvedAmount));
            }
        }
        if (spec.movesMoney()) {
            log.debug("resolved money action tool={} amount_minor={}", spec.name(), resolvedAmount);
        }
    }

    /**
     * Releases a checkout the tool locked while resolving.
     *
     * <p>{@code complete_checkout} locks its checkout during {@code resolve} — that lock is what
     * freezes the amount policy then evaluates. When the action does not go on to execute, the
     * orchestrator that caused the lock is the thing that has to undo it, or a refused payment
     * would leave the customer's basket stuck until it expired.
     */
    private void releaseAnyLock(Caller caller, ToolSpec spec, ResolvedAction resolved) {
        if (spec.operation() != PolicyOperation.CHECKOUT_PAY || resolved == null) {
            return;
        }
        UUID checkoutId = resolved.target().checkoutId();
        if (checkoutId != null) {
            checkoutService.releaseLock(checkoutId);
        }
    }

    /** Credits the conversation's budget, only after the platform accepted the money movement. */
    private void creditBudget(Conversation conversation, ToolSpec spec, ResolvedAction resolved) {
        Long amount = resolved.target().amountMinor();
        if (amount == null || amount <= 0) {
            return;
        }
        if (spec.operation() == PolicyOperation.CHECKOUT_PAY) {
            conversations.recordSpend(conversation.getId(), amount);
        } else if (spec.operation() == PolicyOperation.REFUND_CREATE) {
            conversations.recordRefund(conversation.getId(), amount);
        }
    }

    // ── Plumbing ────────────────────────────────────────────────────────────────────────

    private LlmRequest buildRequest(List<LlmMessage> messages) {
        AgenticProperties.Llm llm = properties.llm();
        // The tool definitions come from the registry, in its deterministic order, on every
        // single call. Caching them here would be a second source of truth with a lifetime.
        return new LlmRequest(systemPrompt.text(), messages, toolRegistry.llmDefinitions(),
                llm.activeModel(), llm.maxTokens(), llm.temperature());
    }

    private static List<LlmMessage> toLlmMessages(List<ConversationMessage> transcript) {
        List<LlmMessage> messages = new ArrayList<>(transcript.size());
        for (ConversationMessage message : transcript) {
            // Tool results are replayed as user-visible context rather than as protocol tool
            // results: their originating tool_use ids belong to earlier requests and pairing
            // them again would be inventing a protocol history that did not happen.
            switch (message.getRole()) {
                case USER -> messages.add(LlmMessage.user(message.getContent()));
                case ASSISTANT -> messages.add(LlmMessage.assistant(message.getContent(), List.of()));
                case TOOL -> messages.add(LlmMessage.user("[tool result] " + message.getContent()));
            }
        }
        return messages;
    }

    private UUID paymentIdOf(ToolResult result) {
        if (result.payload() == null) {
            return null;
        }
        try {
            Map<?, ?> payload = objectMapper.convertValue(result.payload(), Map.class);
            Object paymentId = payload.get("paymentId");
            return paymentId == null ? null : UUID.fromString(String.valueOf(paymentId));
        } catch (RuntimeException e) {
            // A payload without a payment id is ordinary — a catalogue search has none. The
            // action's steps carry the authoritative payment reference either way.
            return null;
        }
    }

    private String render(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (RuntimeException e) {
            log.warn("A tool result could not be rendered for the model.", e);
            return "{\"ok\":false,\"errorCode\":\"result_render_failed\"}";
        }
    }

    /** {@code Map.of} rejects nulls, and a failure payload legitimately has them. */
    private static Map<String, Object> mapOf(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    private ToolOutcome rejected(LlmToolCall call, UUID actionId, String toolName, String errorCode,
                                 String message) {
        return new ToolOutcome(
                LlmToolResult.failure(call.id(), toolName, render(mapOf(
                        "ok", false, "errorCode", errorCode, "message", message))),
                summaryOf(actionId, toolName, "FAILED", null, errorCode, message),
                null);
    }

    private static AgentTurnResult.ActionSummary summaryOf(UUID actionId, String toolName, String state,
                                                           String policyDecision, String errorCode,
                                                           String message) {
        return new AgentTurnResult.ActionSummary(actionId, toolName, state, policyDecision, false,
                errorCode, message);
    }

    private AgentTurnResult limitReached(UUID conversationId, List<AgentTurnResult.ActionSummary> actions,
                                         String reply) {
        metrics.turnCompleted(AgentTurnResult.AgentStopReason.LIMIT_REACHED.name());
        log.info("agent turn stopped at a configured limit conversation={} actions={}", conversationId,
                actions.size());
        return AgentTurnResult.stopped(conversationId, reply, actions,
                AgentTurnResult.AgentStopReason.LIMIT_REACHED);
    }

    private AgentTurnResult stopped(UUID conversationId, List<AgentTurnResult.ActionSummary> actions,
                                    AgentTurnResult.AgentStopReason stopReason, String reply) {
        metrics.turnCompleted(stopReason.name());
        conversations.append(conversationId, ConversationMessage.Role.ASSISTANT, reply);
        return AgentTurnResult.stopped(conversationId, reply, actions, stopReason);
    }

    private static String replyOf(LlmResponse response) {
        return response.text() == null || response.text().isBlank()
                ? "Is there anything else I can help with?"
                : response.text();
    }

    private String modelName() {
        return Optional.ofNullable(properties.llm().activeModel()).orElse(llmClient.providerName());
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "(none)";
        }
        String cleaned = value.replaceAll("[^A-Za-z0-9_.-]", "");
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max);
    }
}
