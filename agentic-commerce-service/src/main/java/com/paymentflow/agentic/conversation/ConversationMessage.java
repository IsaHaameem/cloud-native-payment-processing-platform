package com.paymentflow.agentic.conversation;

import com.paymentflow.agentic.action.Redactor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * One turn of a conversation, persisted.
 *
 * <p><b>Context, never truth.</b> These rows are what the model is shown so a conversation
 * reads coherently; they are not a record of what is financially the case. An assistant turn
 * saying a payment succeeded is a sentence, and {@code AgentRuntime} re-reads the payment
 * before acting on it regardless. The authoritative record of what actually happened is
 * {@code agent_actions} and its steps.
 *
 * <p><b>Redacted on the way in, not on the way out.</b> {@link #of} passes every message
 * through {@link Redactor} before it is stored, so a credential a customer pasted into the chat
 * is never written to the database at all. Redacting at read time would mean the secret was
 * durable and merely hidden — which is not the same thing, and is unrecoverable once the row
 * exists.
 */
@Entity
@Table(name = "conversation_messages")
public class ConversationMessage {

    /** Bounds one turn's contribution to the prompt. A pasted novel is a cost, not a conversation. */
    private static final int MAX_CONTENT_LENGTH = 8000;

    public enum Role {
        USER,
        ASSISTANT,
        /** A structured tool result, stored so the transcript can be replayed and audited. */
        TOOL
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false, updatable = false)
    private Conversation conversation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private Role role;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String content;

    @Column(name = "sequence_no", nullable = false, updatable = false)
    private int sequenceNo;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ConversationMessage() {
        // Required by JPA.
    }

    private ConversationMessage(Conversation conversation, Role role, String content, int sequenceNo) {
        this.conversation = conversation;
        this.role = role;
        this.content = content;
        this.sequenceNo = sequenceNo;
    }

    /**
     * Creates a message, redacting and bounding its content first.
     *
     * <p>The only constructor available, so there is no path by which raw text reaches the
     * table. A blank message becomes a single space rather than null: the column is
     * {@code not null}, and an assistant turn that produced only tool calls legitimately has no
     * text of its own.
     */
    public static ConversationMessage of(Conversation conversation, Role role, String content,
                                         int sequenceNo) {
        String redacted = Redactor.redactText(content);
        if (redacted == null || redacted.isBlank()) {
            redacted = " ";
        }
        if (redacted.length() > MAX_CONTENT_LENGTH) {
            redacted = redacted.substring(0, MAX_CONTENT_LENGTH - 3) + "...";
        }
        return new ConversationMessage(conversation, role, redacted, sequenceNo);
    }

    public UUID getId() {
        return id;
    }

    public UUID getConversationId() {
        return conversation == null ? null : conversation.getId();
    }

    public Role getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public int getSequenceNo() {
        return sequenceNo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
