package com.paymentflow.agentic.web;

import com.paymentflow.agentic.action.AgentAction;
import com.paymentflow.agentic.action.AgentActionStep;
import com.paymentflow.agentic.conversation.Conversation;
import com.paymentflow.agentic.conversation.ConversationMessage;
import com.paymentflow.agentic.runtime.AgentTurnResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * The shapes the demo API speaks.
 *
 * <p>Every one of them is a projection this service chose. Nothing here exposes a policy
 * threshold, a credential, an internal identifier belonging to another service, or a payment
 * field the platform did not return — the browser talks to the agent, and the agent's answers
 * are already the redacted, structured view.
 *
 * <p><b>These are not part of the published {@code /v1} contract.</b> This is a hackathon
 * surface, deliberately outside {@code docs/openapi.yaml} and outside the three contract gates
 * that guard it (AD-8), which is exactly why it is free to look like this.
 */
public final class AgentDtos {

    private AgentDtos() {
    }

    // ── Requests ────────────────────────────────────────────────────────────────────────

    /**
     * @param sessionRef the caller's own session identifier. Scopes conversations to a browser
     *                   session; it is not an authentication credential and is not treated as one
     */
    public record StartConversationRequest(@NotBlank @Size(max = 128) String sessionRef) {
    }

    /** One customer message. Bounded because it becomes part of a prompt. */
    public record SendMessageRequest(@NotBlank @Size(max = 4000) String message) {
    }

    /** @param decidedBy who is approving, for the audit trail. Recorded, never authenticated against */
    public record ApprovalDecisionRequest(@Size(max = 128) String decidedBy, @Size(max = 500) String reason) {
    }

    // ── Responses ───────────────────────────────────────────────────────────────────────

    public record ConversationResponse(
            String id,
            String status,
            String sessionRef,
            long spentMinor,
            long refundedMinor,
            int toolCallCount,
            Instant createdAt,
            List<MessageResponse> messages) {

        public static ConversationResponse of(Conversation conversation, List<ConversationMessage> messages) {
            return new ConversationResponse(
                    conversation.getId().toString(),
                    conversation.getStatus().name(),
                    conversation.getSessionRef(),
                    conversation.getSpentMinor(),
                    conversation.getRefundedMinor(),
                    conversation.getToolCallCount(),
                    conversation.getCreatedAt(),
                    messages.stream().map(MessageResponse::of).toList());
        }
    }

    public record MessageResponse(String role, String content, int sequenceNo, Instant createdAt) {

        public static MessageResponse of(ConversationMessage message) {
            return new MessageResponse(message.getRole().name(), message.getContent(),
                    message.getSequenceNo(), message.getCreatedAt());
        }
    }

    /**
     * A conversation as it appears in the list (G-4) — the header facts, without the transcript.
     * The transcript is on {@code GET /api/agentic/conversations/{id}}.
     */
    public record ConversationSummary(
            String id,
            String status,
            String sessionRef,
            long spentMinor,
            long refundedMinor,
            int toolCallCount,
            Instant createdAt) {

        public static ConversationSummary of(Conversation conversation) {
            return new ConversationSummary(
                    conversation.getId().toString(),
                    conversation.getStatus().name(),
                    conversation.getSessionRef(),
                    conversation.getSpentMinor(),
                    conversation.getRefundedMinor(),
                    conversation.getToolCallCount(),
                    conversation.getCreatedAt());
        }
    }

    /**
     * One agent turn.
     *
     * <p>{@code stopReason} is what a client should branch on. {@code reply} is prose for the
     * customer, and a UI that inferred a payment outcome from it would be reintroducing exactly
     * the failure the rest of this service is built to prevent.
     */
    public record TurnResponse(
            String conversationId,
            String reply,
            String stopReason,
            String approvalId,
            List<ActionResponse> actions) {

        public static TurnResponse of(AgentTurnResult result) {
            return new TurnResponse(
                    result.conversationId().toString(),
                    result.reply(),
                    result.stopReason().name(),
                    result.approvalId() == null ? null : result.approvalId().toString(),
                    result.actions().stream()
                            .map(action -> new ActionResponse(
                                    action.actionId() == null ? null : action.actionId().toString(),
                                    action.toolName(), action.state(), action.policyDecision(),
                                    action.ok(), action.errorCode(), action.message()))
                            .toList());
        }
    }

    public record ActionResponse(
            String actionId,
            String toolName,
            String state,
            String policyDecision,
            boolean ok,
            String errorCode,
            String message) {
    }

    /**
     * The full trail for one action, as the demo's audit view shows it.
     *
     * <p>This is the shape the whole story is told in: what the model asked for, what policy
     * decided, whether a human was involved, and every platform call it produced — each with the
     * derived idempotency key that proves a retry was a replay rather than a second charge.
     */
    public record ActionTrailResponse(
            String id,
            String toolName,
            String toolCategory,
            String state,
            String policyDecision,
            String approvalId,
            String checkoutId,
            String paymentId,
            String inputSummary,
            String failureCode,
            String failureMessage,
            Long budgetRemainingMinor,
            String correlationId,
            String llmModel,
            String promptVersion,
            Instant createdAt,
            Instant completedAt,
            List<StepResponse> steps) {

        public static ActionTrailResponse of(AgentAction action) {
            return new ActionTrailResponse(
                    action.getId().toString(),
                    action.getToolName(),
                    action.getToolCategory(),
                    action.getState().name(),
                    action.getPolicyDecision() == null ? null : action.getPolicyDecision().name(),
                    action.getApprovalId() == null ? null : action.getApprovalId().toString(),
                    action.getCheckoutId() == null ? null : action.getCheckoutId().toString(),
                    action.getPaymentId() == null ? null : action.getPaymentId().toString(),
                    action.getInputSummary(),
                    action.getFailureCode(),
                    action.getFailureMessage(),
                    action.getBudgetRemainingMinor(),
                    action.getCorrelationId(),
                    action.getLlmModel(),
                    action.getPromptVersion(),
                    action.getCreatedAt(),
                    action.getCompletedAt(),
                    action.getSteps().stream().map(StepResponse::of).toList());
        }
    }

    /**
     * One platform call.
     *
     * @param idempotencyKey the derived key. Exposed because it is the evidence: a step in state
     *                       {@code REPLAYED} carrying a key an earlier step also carried is what
     *                       makes "the agent did not double-charge" checkable rather than claimed
     */
    public record StepResponse(
            int sequenceNo,
            String operation,
            String state,
            String idempotencyKey,
            String requestId,
            Integer httpStatus,
            String paymentId,
            String failureCode,
            Instant createdAt,
            Instant completedAt) {

        public static StepResponse of(AgentActionStep step) {
            return new StepResponse(
                    step.getSequenceNo(),
                    step.getOperation(),
                    step.getState().name(),
                    step.getIdempotencyKey(),
                    step.getRequestId(),
                    step.getHttpStatus(),
                    step.getPaymentId() == null ? null : step.getPaymentId().toString(),
                    step.getFailureCode(),
                    step.getCreatedAt(),
                    step.getCompletedAt());
        }
    }
}
