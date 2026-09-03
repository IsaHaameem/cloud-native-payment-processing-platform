package com.paymentflow.agentic.tool;

import com.paymentflow.agentic.policy.PolicyRequest;

import java.util.Objects;
import java.util.UUID;

/**
 * Who the tool is acting for. Established from the caller's own session, never from the
 * model's arguments.
 *
 * <p>No tool takes a merchant id, a mode or a conversation id as an argument, and none ever
 * will: those arrive here, from the authenticated context, and a tool that accepted them
 * would be a tool the model could point at another tenant. The one thing the model supplies
 * is <em>which</em> product or checkout inside this tenant to act on, and every repository in
 * this service resolves that through a {@code (merchantId, mode)}-scoped finder that 404s
 * anything else.
 *
 * @param correlationId generated per action, before the platform call, and sent as
 *                      {@code X-Correlation-Id}. Chosen here rather than read back from a
 *                      response so it can be logged before the call it identifies — the only
 *                      ordering that survives a timeout. A UUID string, because the published
 *                      contract declares that header as {@code format: uuid}.
 * @param principal     the identity written to {@code policy_decisions.actor}.
 * @param agentActionId the already-committed action row this call's platform steps hang off.
 *                      Present because a composite money tool makes several platform calls and
 *                      only the tool knows how many, so the tool records its own steps through
 *                      {@code AgentActionJournal}. Null for a call made outside an action —
 *                      which no money tool ever is
 */
public record ToolContext(
        UUID merchantId,
        String mode,
        UUID conversationId,
        String sessionRef,
        String principal,
        String correlationId,
        UUID agentActionId) {

    public ToolContext {
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(conversationId, "conversationId");
    }

    /**
     * The action id, required.
     *
     * <p>A money tool cannot proceed without one: a platform call with nowhere to record its
     * step would be a charge this service could not afterwards prove it had made.
     */
    public UUID requireAgentActionId() {
        if (agentActionId == null) {
            throw new IllegalStateException(
                    "A money tool was invoked outside an agent action, so its platform calls could not "
                            + "be recorded. This is a pipeline defect, not a caller error.");
        }
        return agentActionId;
    }

    /** The actor half of a policy request, projected once so no call site assembles its own. */
    public PolicyRequest.Actor toPolicyActor() {
        return new PolicyRequest.Actor(merchantId, mode, sessionRef, principal);
    }
}
