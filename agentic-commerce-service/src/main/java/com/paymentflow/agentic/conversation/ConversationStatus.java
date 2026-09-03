package com.paymentflow.agentic.conversation;

/**
 * Whether a conversation may still act.
 *
 * <p>Two states, not more. A conversation is a budget holder and an audit anchor, not a
 * workflow: everything interesting about what happened inside it lives on its actions. Adding
 * states here would mean adding transitions nothing reads.
 */
public enum ConversationStatus {

    /** Tools may be called, subject to policy. */
    ACTIVE,

    /**
     * Terminal. Refused by {@code PolicyRule.CONVERSATION_ACTIVE} before any rule about
     * amounts is reached, so closing a conversation stops <em>every</em> tool, not only the
     * ones that move money.
     */
    CLOSED;

    public boolean isActive() {
        return this == ACTIVE;
    }
}
