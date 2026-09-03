package com.paymentflow.agentic.runtime;

import java.util.List;
import java.util.UUID;

/**
 * Everything one turn of the agent produced: what it said, what it did, and why it stopped.
 *
 * <p><b>{@link #stopReason} is the field a caller should branch on, not the reply text.</b> The
 * reply is prose — sometimes the model's, sometimes this service's — and a UI that decided
 * whether a payment had happened by reading it would be doing exactly what the whole design
 * exists to prevent. The structured fields say what occurred; the text is for the customer.
 *
 * @param reply       what to show the customer. Server-authored whenever the turn ended for a
 *                    reason the model was never told about — a limit, an outage, an approval
 * @param actions     one entry per tool call the model made this turn, including the ones that
 *                    were refused. The audit view reads these
 * @param approvalId  the approval now waiting on a human, when {@code stopReason} is
 *                    {@link AgentStopReason#APPROVAL_REQUIRED}. Null otherwise
 */
public record AgentTurnResult(
        UUID conversationId,
        String reply,
        List<ActionSummary> actions,
        AgentStopReason stopReason,
        UUID approvalId) {

    public AgentTurnResult {
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    /** Why the turn ended. */
    public enum AgentStopReason {
        /** The model finished its reply. Ordinary completion. */
        COMPLETED,

        /**
         * A money action needs a human. <b>The financial operation did not run.</b> The turn
         * stops here rather than continuing, so the model cannot narrate its way past the gate.
         */
        APPROVAL_REQUIRED,

        /**
         * A configured ceiling was reached — iterations, or wall-clock time. Safe termination:
         * whatever executed before the limit keeps its own recorded outcome, and the reply says
         * the assistant stopped rather than pretending it finished.
         */
        LIMIT_REACHED,

        /** The model could not be reached. Nothing is claimed about what did or did not happen. */
        LLM_UNAVAILABLE,

        /** The model answered with something unreadable. Nothing was executed from it. */
        LLM_OUTPUT_INVALID,

        /** Something else failed. The reply says so plainly rather than inventing an outcome. */
        FAILED;

        /** Whether the customer is waiting on someone rather than on the assistant. */
        public boolean isPending() {
            return this == APPROVAL_REQUIRED;
        }
    }

    /**
     * One tool call, as the audit view shows it.
     *
     * @param actionId       the {@code agent_actions} row, which is where the full trail lives
     * @param policyDecision {@code PERMIT}, {@code REFUSE} or {@code REQUIRES_APPROVAL}, or null
     *                       if the call never reached the policy engine — a schema rejection, for
     *                       instance, which is refused before an amount is ever resolved
     * @param ok             whether the tool produced a successful result. A refusal, a decline
     *                       and an approval requirement are all {@code false}
     */
    public record ActionSummary(
            UUID actionId,
            String toolName,
            String state,
            String policyDecision,
            boolean ok,
            String errorCode,
            String message) {
    }

    static AgentTurnResult completed(UUID conversationId, String reply, List<ActionSummary> actions) {
        return new AgentTurnResult(conversationId, reply, actions, AgentStopReason.COMPLETED, null);
    }

    static AgentTurnResult approvalRequired(UUID conversationId, String reply,
                                            List<ActionSummary> actions, UUID approvalId) {
        return new AgentTurnResult(conversationId, reply, actions, AgentStopReason.APPROVAL_REQUIRED,
                approvalId);
    }

    static AgentTurnResult stopped(UUID conversationId, String reply, List<ActionSummary> actions,
                                   AgentStopReason stopReason) {
        return new AgentTurnResult(conversationId, reply, actions, stopReason, null);
    }
}
