package com.paymentflow.agentic.approval;

import com.paymentflow.agentic.error.AgenticErrorCode;
import com.paymentflow.agentic.error.AgenticException;
import com.paymentflow.agentic.policy.PolicyOperation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A human's decision about one agent action, frozen to the parameters it was asked about.
 *
 * <p>Three properties make this an approval rather than a note that someone once clicked yes:
 *
 * <ol>
 *   <li><b>It binds.</b> The merchant, mode, operation, target and amount are copied onto this
 *       row when the approval is <em>requested</em>, and {@link #redeem} re-compares them
 *       against what would actually execute. An action that changed after approval is refused,
 *       not executed.</li>
 *   <li><b>It expires.</b> Both before a decision and after one. A grant left unredeemed past
 *       {@code expiresAt} is dead — an approver's judgement is about a situation, and this is
 *       how long the platform is prepared to assume the situation has not moved.</li>
 *   <li><b>It is spent exactly once.</b> {@link ApprovalState#CONSUMED} is reached on
 *       redemption, so a retry of the same tool call finds no live approval and stops.</li>
 * </ol>
 *
 * <p>There is deliberately <b>no method that changes what an approval covers</b>. Not the
 * amount, not the currency, not the checkout, not the payment, not the operation. The only
 * transitions are decide, expire and redeem. An approval that could be edited after being
 * granted would be a signature on a blank cheque.
 *
 * <p>The schema's {@code uq_approvals_action} enforces one approval per action, so a second
 * request for the same action fails at the database as well as here — an action with two live
 * approvals would make "who authorised this" ambiguous at exactly the moment it matters most.
 */
@Entity
@Table(name = "approvals")
public class Approval {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(nullable = false, updatable = false, length = 4)
    private String mode;

    /**
     * Mapped as a plain id rather than an association. The action owns the approval reference
     * ({@code agent_actions.approval_id}), not the other way round, and a bidirectional pair
     * here would give two writable ends to one relationship whose whole point is that it
     * cannot be re-pointed.
     */
    @Column(name = "agent_action_id", nullable = false, updatable = false, unique = true)
    private UUID agentActionId;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Column(name = "tool_name", nullable = false, updatable = false, length = 64)
    private String toolName;

    /**
     * The bound operation. An enum rather than the tool's name, because a tool name is an
     * implementation label that can be renamed or re-pointed, and the operation is what the
     * approver was actually agreeing to.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "requested_operation", nullable = false, updatable = false, length = 64)
    private PolicyOperation requestedOperation;

    @Column(name = "checkout_id", updatable = false)
    private UUID checkoutId;

    @Column(name = "payment_id", updatable = false)
    private UUID paymentId;

    /** The exact amount the approver agreed to, frozen at request time and never writable again. */
    @Column(name = "amount_minor", updatable = false)
    private Long amountMinor;

    @Column(updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ApprovalState state;

    @Column(length = 500)
    private String reason;

    @Column(name = "decided_by", length = 128)
    private String decidedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    protected Approval() {
        // Required by JPA.
    }

    private Approval(UUID agentActionId, UUID conversationId, String toolName, ApprovalBinding binding,
                     String reason, Instant expiresAt) {
        this.agentActionId = agentActionId;
        this.conversationId = conversationId;
        this.toolName = toolName;
        this.merchantId = binding.merchantId();
        this.mode = binding.mode();
        this.requestedOperation = binding.operation();
        this.checkoutId = binding.checkoutId();
        this.paymentId = binding.paymentId();
        this.amountMinor = binding.amountMinor();
        this.currency = binding.currency();
        this.reason = truncate(reason);
        this.expiresAt = expiresAt;
        this.state = ApprovalState.PENDING;
    }

    /**
     * Opens an approval request. The action it belongs to is already recorded as
     * {@code APPROVAL_REQUIRED}, and no financial call has been made.
     */
    public static Approval request(UUID agentActionId, UUID conversationId, String toolName,
                                   ApprovalBinding binding, String reason, Instant expiresAt) {
        return new Approval(agentActionId, conversationId, toolName, binding, reason, expiresAt);
    }

    // ── Decisions ───────────────────────────────────────────────────────────────────────

    /**
     * A human said yes.
     *
     * <p>Expiry is checked <em>before</em> the state check and lands the row in
     * {@link ApprovalState#EXPIRED}, so a request that timed out while nobody was looking is
     * recorded as having timed out rather than as having been approved late.
     */
    public void approve(String decidedBy, Instant now) {
        expireIfDue(now);
        requirePending();
        this.state = ApprovalState.APPROVED;
        this.decidedBy = decidedBy;
        this.decidedAt = now;
    }

    /** A human said no. Terminal, and the action it belongs to is refused. */
    public void deny(String decidedBy, String reason, Instant now) {
        expireIfDue(now);
        requirePending();
        this.state = ApprovalState.DENIED;
        this.decidedBy = decidedBy;
        this.decidedAt = now;
        if (reason != null && !reason.isBlank()) {
            this.reason = truncate(reason);
        }
    }

    /**
     * Spends this approval on one execution, having first proved it still covers that
     * execution.
     *
     * <p><b>The order of the three checks is the whole security property.</b> Expiry, then
     * redeemability, then the binding — and the binding is re-derived from server-side facts
     * at the moment of execution, never taken from the caller. An approval that is live but
     * covers a different amount stops here, with the field that moved named in the failure.
     *
     * @param actual the binding as it stands now, resolved from the checkout or payment being
     *               acted on rather than from anything the model or the caller supplied
     * @throws AgenticException if the approval has expired, is not redeemable, or no longer
     *                          covers {@code actual}
     */
    public void redeem(ApprovalBinding actual, Instant now) {
        expireIfDue(now);
        if (state == ApprovalState.EXPIRED) {
            throw new AgenticException(AgenticErrorCode.APPROVAL_EXPIRED,
                    "This approval expired at %s and can no longer be used.".formatted(expiresAt));
        }
        if (!state.isRedeemable()) {
            throw new AgenticException(AgenticErrorCode.APPROVAL_NOT_PENDING,
                    "This approval is %s and cannot authorise an execution.".formatted(state));
        }
        String difference = binding().firstDifferenceFrom(actual);
        if (difference != null) {
            throw new AgenticException(AgenticErrorCode.APPROVAL_AMOUNT_CHANGED,
                    ("The %s has changed since this approval was granted, so the approval no longer covers "
                            + "this action. Request approval again.").formatted(difference));
        }
        this.state = ApprovalState.CONSUMED;
    }

    /**
     * Moves a live approval to {@link ApprovalState#EXPIRED} if its time has passed.
     *
     * <p>Applies to {@code APPROVED} as well as {@code PENDING}, which is the point: a grant
     * nobody redeemed in time is exactly as dead as a request nobody answered.
     *
     * <p>Evaluated on read rather than by a scheduled sweeper, the same way checkout expiry is.
     * The state a caller sees is therefore always current, without this service running a job
     * whose only purpose is to write a state nobody has asked about yet.
     */
    public void expireIfDue(Instant now) {
        if (!state.isTerminal() && now.isAfter(expiresAt)) {
            this.state = ApprovalState.EXPIRED;
            this.decidedAt = now;
        }
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }

    /** What this approval covers, as one value. Never assembled by a caller. */
    public ApprovalBinding binding() {
        return new ApprovalBinding(merchantId, mode, requestedOperation, checkoutId, paymentId, amountMinor,
                currency);
    }

    private void requirePending() {
        if (state != ApprovalState.PENDING) {
            throw new AgenticException(AgenticErrorCode.APPROVAL_NOT_PENDING,
                    "This approval is already %s and cannot be decided again.".formatted(state));
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 497) + "...";
    }

    // ── Accessors ───────────────────────────────────────────────────────────────────────

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getMode() {
        return mode;
    }

    public UUID getAgentActionId() {
        return agentActionId;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public String getToolName() {
        return toolName;
    }

    public PolicyOperation getRequestedOperation() {
        return requestedOperation;
    }

    public UUID getCheckoutId() {
        return checkoutId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public Long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public ApprovalState getState() {
        return state;
    }

    public String getReason() {
        return reason;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
