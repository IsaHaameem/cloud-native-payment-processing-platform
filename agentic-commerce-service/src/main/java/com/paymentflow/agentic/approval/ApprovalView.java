package com.paymentflow.agentic.approval;

import java.time.Instant;

/**
 * The projection of an approval that leaves this service — to the demo API's approval queue,
 * and to the model as the reason a money tool stopped.
 *
 * <p>The amount and currency are here because they are the whole point: an approver who
 * cannot see what they are approving is not approving anything. What is <em>not</em> here is
 * any path to change them — this is a read shape, and {@link Approval} has no method that
 * would let a caller re-point what an approval covers even if one existed.
 */
public record ApprovalView(
        String id,
        String agentActionId,
        String conversationId,
        String toolName,
        String operation,
        String checkoutId,
        String paymentId,
        Long amountMinor,
        String currency,
        String state,
        String reason,
        String decidedBy,
        Instant createdAt,
        Instant expiresAt,
        Instant decidedAt) {

    public static ApprovalView of(Approval approval) {
        return new ApprovalView(
                approval.getId().toString(),
                approval.getAgentActionId().toString(),
                approval.getConversationId().toString(),
                approval.getToolName(),
                approval.getRequestedOperation().name(),
                approval.getCheckoutId() == null ? null : approval.getCheckoutId().toString(),
                approval.getPaymentId() == null ? null : approval.getPaymentId().toString(),
                approval.getAmountMinor(),
                approval.getCurrency(),
                approval.getState().name(),
                approval.getReason(),
                approval.getDecidedBy(),
                approval.getCreatedAt(),
                approval.getExpiresAt(),
                approval.getDecidedAt());
    }
}
