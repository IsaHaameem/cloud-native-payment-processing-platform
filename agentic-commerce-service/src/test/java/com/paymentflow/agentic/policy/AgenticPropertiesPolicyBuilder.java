package com.paymentflow.agentic.policy;

import com.paymentflow.agentic.config.AgenticProperties;

/**
 * A mutable builder over the immutable policy record, for tests that vary one threshold.
 *
 * <p>Exists so a test asserting what happens when a single cap is blanked does not have to
 * restate the other eight values — restating them is how a test ends up silently asserting
 * against the wrong baseline after someone changes a default.
 */
final class AgenticPropertiesPolicyBuilder {

    private String version = PolicyFixtures.POLICY_VERSION;
    private String currency = PolicyFixtures.CURRENCY;
    private long maxPaymentAmountMinor = PolicyFixtures.MAX_PAYMENT;
    private long maxConversationSpendMinor = PolicyFixtures.MAX_CONVERSATION_SPEND;
    private long refundApprovalThresholdMinor = PolicyFixtures.REFUND_APPROVAL_THRESHOLD;
    private long maxRefundAmountMinor = PolicyFixtures.MAX_REFUND;
    private long maxConversationRefundMinor = PolicyFixtures.MAX_CONVERSATION_REFUND;
    private int maxToolCallsPerConversation = PolicyFixtures.MAX_TOOL_CALLS;
    private int approvalTtlMinutes = PolicyFixtures.APPROVAL_TTL_MINUTES;

    AgenticPropertiesPolicyBuilder version(String value) {
        this.version = value;
        return this;
    }

    AgenticPropertiesPolicyBuilder currency(String value) {
        this.currency = value;
        return this;
    }

    AgenticPropertiesPolicyBuilder maxPaymentAmountMinor(long value) {
        this.maxPaymentAmountMinor = value;
        return this;
    }

    AgenticPropertiesPolicyBuilder maxConversationSpendMinor(long value) {
        this.maxConversationSpendMinor = value;
        return this;
    }

    AgenticPropertiesPolicyBuilder refundApprovalThresholdMinor(long value) {
        this.refundApprovalThresholdMinor = value;
        return this;
    }

    AgenticPropertiesPolicyBuilder maxRefundAmountMinor(long value) {
        this.maxRefundAmountMinor = value;
        return this;
    }

    AgenticPropertiesPolicyBuilder maxConversationRefundMinor(long value) {
        this.maxConversationRefundMinor = value;
        return this;
    }

    AgenticPropertiesPolicyBuilder maxToolCallsPerConversation(int value) {
        this.maxToolCallsPerConversation = value;
        return this;
    }

    AgenticPropertiesPolicyBuilder approvalTtlMinutes(int value) {
        this.approvalTtlMinutes = value;
        return this;
    }

    AgenticProperties.Policy build() {
        return new AgenticProperties.Policy(version, currency, maxPaymentAmountMinor, maxConversationSpendMinor,
                refundApprovalThresholdMinor, maxRefundAmountMinor, maxConversationRefundMinor,
                maxToolCallsPerConversation, approvalTtlMinutes);
    }
}
