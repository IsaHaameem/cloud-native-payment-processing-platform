package com.paymentflow.agentic.conversation;

import com.paymentflow.agentic.policy.PolicyRequest;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * One agent conversation, and the budget it spends against.
 *
 * <p>The counters here are the reason this is an entity rather than a transient session
 * object. A budget check runs before every money action, and recomputing cumulative spend
 * from the action log each time would turn the hot path of the most safety-critical decision
 * this service makes into a table scan. Keeping them on the row makes the check a field read.
 *
 * <p><b>{@code @Version} is load-bearing.</b> Two tool calls racing inside one conversation
 * would otherwise both read the same {@code spentMinor}, both pass the budget check, and both
 * charge — a lost update that spends the budget twice. Optimistic locking makes the second
 * one fail and retry against the counter the first actually wrote.
 *
 * <p>The counters only ever move forward, and only after the platform has accepted an action.
 * Crediting spend before execution would let a failed payment consume budget; crediting it
 * lazily would let a fast second action slip past the check.
 */
@Entity
@Table(name = "conversations")
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(nullable = false, updatable = false, length = 4)
    private String mode;

    @Column(name = "session_ref", nullable = false, updatable = false, length = 128)
    private String sessionRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ConversationStatus status;

    @Column(name = "spent_minor", nullable = false)
    private long spentMinor;

    @Column(name = "refunded_minor", nullable = false)
    private long refundedMinor;

    @Column(name = "tool_call_count", nullable = false)
    private int toolCallCount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Conversation() {
        // Required by JPA.
    }

    private Conversation(UUID merchantId, String mode, String sessionRef) {
        this.merchantId = merchantId;
        this.mode = mode;
        this.sessionRef = sessionRef;
        this.status = ConversationStatus.ACTIVE;
    }

    public static Conversation start(UUID merchantId, String mode, String sessionRef) {
        return new Conversation(merchantId, mode, sessionRef);
    }

    // ── Counters ────────────────────────────────────────────────────────────────────────

    /**
     * Counts one tool call against the conversation's ceiling.
     *
     * <p>Incremented when the call is <em>made</em>, not when it succeeds. The ceiling exists
     * to bound a runaway agent, and an agent looping on failures is exactly the case it has to
     * bound — counting only successes would make the ceiling unreachable in the one scenario
     * it was written for.
     */
    public void recordToolCall() {
        this.toolCallCount++;
    }

    /** Credits a payment the platform accepted. Never called speculatively. */
    public void recordSpend(long amountMinor) {
        requirePositive(amountMinor);
        this.spentMinor = Math.addExact(this.spentMinor, amountMinor);
    }

    /** Credits a refund the platform accepted. Never called speculatively. */
    public void recordRefund(long amountMinor) {
        requirePositive(amountMinor);
        this.refundedMinor = Math.addExact(this.refundedMinor, amountMinor);
    }

    public void close() {
        this.status = ConversationStatus.CLOSED;
    }

    public boolean isActive() {
        return status.isActive();
    }

    /**
     * The counters as the policy engine wants them.
     *
     * <p>Projected here rather than assembled at the call site so there is exactly one place
     * where a conversation becomes a policy input — a second, subtly different projection is
     * how a budget check ends up reading a stale or wrong number.
     */
    public PolicyRequest.Conversation toPolicyConversation() {
        return new PolicyRequest.Conversation(id, isActive(), spentMinor, refundedMinor, toolCallCount);
    }

    private static void requirePositive(long amountMinor) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("A conversation counter can only move forward, by a positive "
                    + "amount, but was asked to move by " + amountMinor + ".");
        }
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

    public String getSessionRef() {
        return sessionRef;
    }

    public ConversationStatus getStatus() {
        return status;
    }

    public long getSpentMinor() {
        return spentMinor;
    }

    public long getRefundedMinor() {
        return refundedMinor;
    }

    public int getToolCallCount() {
        return toolCallCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
