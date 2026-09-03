package com.paymentflow.agentic.web;

import com.paymentflow.agentic.action.ActionState;
import com.paymentflow.agentic.action.AgentActionRepository;
import com.paymentflow.agentic.approval.ApprovalRepository;
import com.paymentflow.agentic.approval.ApprovalState;
import com.paymentflow.agentic.conversation.ConversationRepository;
import com.paymentflow.agentic.policy.PolicyDecision;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * The merchant-scoped aggregate over what the agent has done (G-1).
 *
 * <h2>Persisted data only</h2>
 *
 * <p>Every number here is a {@code count} over a table this service owns — {@code agent_actions},
 * {@code approvals}, {@code conversations} — filtered by {@code (merchant_id, mode)} and an
 * optional time window on {@code created_at}. Nothing is read from Prometheus: those meters exist
 * ({@code agentic_*_total}) but a browser cannot scrape them and they are process-lifetime, not
 * per-merchant. This endpoint is the merchant-facing answer the meters are not.
 *
 * <p><b>A demo approval is not a financial success.</b> {@code payments.agentInitiated} counts
 * actions that produced a {@code payment_id}; whether any of those was a real cardholder
 * authorisation or a Razorpay {@code order_accepted} stand-in is a per-payment fact carried on
 * the provider decision (G-6), never rolled up here as "success".
 */
@Service
@Transactional(readOnly = true)
public class SummaryService {

    private final ConversationRepository conversations;
    private final AgentActionRepository actions;
    private final ApprovalRepository approvals;

    public SummaryService(ConversationRepository conversations, AgentActionRepository actions,
                          ApprovalRepository approvals) {
        this.conversations = conversations;
        this.actions = actions;
        this.approvals = approvals;
    }

    public record Window(Instant from, Instant to) {
    }

    public record ConversationCounts(long total) {
    }

    public record ActionCounts(long total, long executed, long refused, long failed, long approvalRequired) {
    }

    public record PolicyCounts(long permit, long refuse, long requiresApproval) {
    }

    public record ApprovalCounts(long pending, long approved, long denied, long expired, long consumed) {
    }

    public record PaymentCounts(long agentInitiated) {
    }

    public record SummaryView(
            Window window,
            ConversationCounts conversations,
            ActionCounts actions,
            PolicyCounts policyDecisions,
            ApprovalCounts approvals,
            PaymentCounts payments,
            String source) {
    }

    /**
     * @param from inclusive lower bound; {@code null} means the epoch (all time)
     * @param to   exclusive upper bound; {@code null} means now
     */
    public SummaryView summarize(UUID merchantId, String mode, Instant from, Instant to) {
        Instant lower = from == null ? Instant.EPOCH : from;
        Instant upper = to == null ? Instant.now() : to;

        long conversationTotal = conversations
                .countByMerchantIdAndModeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                        merchantId, mode, lower, upper);

        long actionTotal = actions
                .countByMerchantIdAndModeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                        merchantId, mode, lower, upper);
        long executed = actionState(merchantId, mode, ActionState.EXECUTED, lower, upper);
        long refused = actionState(merchantId, mode, ActionState.REFUSED, lower, upper);
        long failed = actionState(merchantId, mode, ActionState.FAILED, lower, upper);
        long approvalRequired = actionState(merchantId, mode, ActionState.APPROVAL_REQUIRED, lower, upper);

        long permit = policy(merchantId, mode, PolicyDecision.PERMIT, lower, upper);
        long refuse = policy(merchantId, mode, PolicyDecision.REFUSE, lower, upper);
        long requiresApproval = policy(merchantId, mode, PolicyDecision.REQUIRES_APPROVAL, lower, upper);

        long agentPayments = actions
                .countByMerchantIdAndModeAndPaymentIdIsNotNullAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                        merchantId, mode, lower, upper);

        return new SummaryView(
                new Window(lower, upper),
                new ConversationCounts(conversationTotal),
                new ActionCounts(actionTotal, executed, refused, failed, approvalRequired),
                new PolicyCounts(permit, refuse, requiresApproval),
                new ApprovalCounts(
                        approvalState(merchantId, mode, ApprovalState.PENDING, lower, upper),
                        approvalState(merchantId, mode, ApprovalState.APPROVED, lower, upper),
                        approvalState(merchantId, mode, ApprovalState.DENIED, lower, upper),
                        approvalState(merchantId, mode, ApprovalState.EXPIRED, lower, upper),
                        approvalState(merchantId, mode, ApprovalState.CONSUMED, lower, upper)),
                new PaymentCounts(agentPayments),
                "persisted");
    }

    private long actionState(UUID merchantId, String mode, ActionState state, Instant from, Instant to) {
        return actions.countByMerchantIdAndModeAndStateAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                merchantId, mode, state, from, to);
    }

    private long policy(UUID merchantId, String mode, PolicyDecision decision, Instant from, Instant to) {
        return actions.countByMerchantIdAndModeAndPolicyDecisionAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                merchantId, mode, decision, from, to);
    }

    private long approvalState(UUID merchantId, String mode, ApprovalState state, Instant from, Instant to) {
        return approvals.countByMerchantIdAndModeAndStateAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                merchantId, mode, state, from, to);
    }
}
