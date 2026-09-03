package com.paymentflow.agentic.web;

import com.paymentflow.agentic.approval.Approval;
import com.paymentflow.agentic.approval.ApprovalService;
import com.paymentflow.agentic.approval.ApprovalView;
import com.paymentflow.agentic.conversation.Conversation;
import com.paymentflow.agentic.conversation.ConversationService;
import com.paymentflow.agentic.runtime.AgentRuntime;
import com.paymentflow.agentic.runtime.AgentTurnResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The human end of the approval gate.
 *
 * <p><b>This is the only way past {@code REQUIRES_APPROVAL}, and it is deliberately not
 * something the agent can reach.</b> Approval is an application-level state transition made by
 * a person through this API — not a sentence in a conversation. A model saying "the customer
 * approved it", or a customer typing "I approve", changes nothing here: no tool calls these
 * endpoints, and the runtime has no path to them.
 *
 * <p>Granting an approval executes the action it was granted for, immediately and once. The
 * execution goes back through {@code AgentRuntime.executeApprovedAction}, which re-resolves the
 * facts, re-evaluates policy, and redeems the approval against what it finds — so an approval
 * granted for one amount cannot spend on another, and one left too long cannot spend at all.
 */
@RestController
@RequestMapping("/api/agentic/approvals")
public class ApprovalController {

    private final ApprovalService approvals;
    private final ConversationService conversations;
    private final AgentRuntime runtime;
    private final AgenticCallerContext callerContext;

    public ApprovalController(ApprovalService approvals, ConversationService conversations,
                              AgentRuntime runtime, AgenticCallerContext callerContext) {
        this.approvals = approvals;
        this.conversations = conversations;
        this.runtime = runtime;
        this.callerContext = callerContext;
    }

    /** The queue a person works through. Entries that quietly expired are aged out as they are read. */
    @GetMapping
    public List<ApprovalView> pending() {
        AgenticCallerContext.Caller caller = callerContext.resolve();
        return approvals.findPending(caller.merchantId(), caller.mode()).stream()
                .map(ApprovalView::of).toList();
    }

    @GetMapping("/{approvalId}")
    public ApprovalView get(@PathVariable UUID approvalId) {
        AgenticCallerContext.Caller caller = callerContext.resolve();
        return ApprovalView.of(approvals.require(caller.merchantId(), caller.mode(), approvalId));
    }

    /**
     * A person says yes, and the action runs.
     *
     * <p>Approving and executing are one operation on purpose. Splitting them would leave a
     * window in which an approval is granted but unspent, and something has to decide what
     * happens if nobody ever spends it — which is a scheduler, a queue, and a second way for a
     * refund to happen. Doing it here means the approval's whole life is one request.
     */
    @PostMapping("/{approvalId}/approve")
    public AgentDtos.TurnResponse approve(@PathVariable UUID approvalId,
                                          @Valid @RequestBody AgentDtos.ApprovalDecisionRequest request) {
        AgenticCallerContext.Caller caller = callerContext.resolve();
        String decidedBy = decidedBy(request, caller);
        Approval approval = approvals.approve(caller.merchantId(), caller.mode(), approvalId, decidedBy);
        Conversation conversation = conversations.require(caller.merchantId(), caller.mode(),
                approval.getConversationId());

        AgentTurnResult result = runtime.executeApprovedAction(
                new AgentRuntime.Caller(caller.merchantId(), caller.mode(),
                        conversation.getSessionRef(), "approver:" + decidedBy),
                approvalId);
        return AgentDtos.TurnResponse.of(result);
    }

    /** A person says no. Terminal, and nothing financial happens now or later under this approval. */
    @PostMapping("/{approvalId}/deny")
    public ApprovalView deny(@PathVariable UUID approvalId,
                             @Valid @RequestBody AgentDtos.ApprovalDecisionRequest request) {
        AgenticCallerContext.Caller caller = callerContext.resolve();
        return ApprovalView.of(approvals.deny(caller.merchantId(), caller.mode(), approvalId,
                decidedBy(request, caller), request.reason()));
    }

    /**
     * Who decided, for the trail.
     *
     * <p>An explicit {@code decidedBy} in the body wins — it lets a portal name the actual
     * reviewer. Absent one, the verified caller from the internal context is recorded, which is
     * the session user the portal proxy asserted. Never blank, and never a value a caller chose
     * for another person.
     */
    private static String decidedBy(AgentDtos.ApprovalDecisionRequest request,
                                    AgenticCallerContext.Caller caller) {
        if (request != null && request.decidedBy() != null && !request.decidedBy().isBlank()) {
            return request.decidedBy();
        }
        return caller.actor();
    }
}
