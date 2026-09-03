package com.paymentflow.agentic.action;

import com.paymentflow.agentic.policy.PolicyDecision;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One tool call — the unit the <em>model</em> is accountable for.
 *
 * <p>Its children ({@link AgentActionStep}) are the units the <em>platform</em> is
 * accountable for. The split exists because a composite money tool performs several platform
 * operations, and a single flat row could not honestly record one that created a payment,
 * authorized it, and then failed at capture.
 *
 * <p>Append-and-advance, never delete. {@code inputSummary} holds a redacted, canonical
 * projection of the validated arguments — never raw model output, and never anything that
 * could carry a credential; {@link com.paymentflow.agentic.action.Redactor} is what makes
 * that true rather than intended.
 */
@Entity
@Table(name = "agent_actions")
public class AgentAction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(nullable = false, updatable = false, length = 4)
    private String mode;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    /**
     * Generated here, before the platform call, and sent as {@code X-Correlation-Id}. Not read
     * back from a response: an identifier this service chose is one it can log before the call
     * it identifies, which is the only ordering that survives a timeout.
     */
    @Column(name = "correlation_id", nullable = false, updatable = false, length = 64)
    private String correlationId;

    @Column(name = "tool_name", nullable = false, updatable = false, length = 64)
    private String toolName;

    @Column(name = "tool_category", nullable = false, updatable = false, length = 24)
    private String toolCategory;

    @Column(name = "input_summary", nullable = false, columnDefinition = "text")
    private String inputSummary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ActionState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "policy_decision", length = 24)
    private PolicyDecision policyDecision;

    @Column(name = "approval_id")
    private UUID approvalId;

    @Column(name = "checkout_id")
    private UUID checkoutId;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_message", length = 1000)
    private String failureMessage;

    @Column(name = "budget_remaining_minor")
    private Long budgetRemainingMinor;

    @Column(name = "llm_model", length = 128)
    private String llmModel;

    @Column(name = "prompt_version", length = 32)
    private String promptVersion;

    @OneToMany(mappedBy = "action", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("sequenceNo asc")
    private List<AgentActionStep> steps = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected AgentAction() {
        // Required by JPA.
    }

    private AgentAction(UUID merchantId, String mode, UUID conversationId, String correlationId, String toolName,
                        String toolCategory, String inputSummary, String llmModel, String promptVersion) {
        this.merchantId = merchantId;
        this.mode = mode;
        this.conversationId = conversationId;
        this.correlationId = correlationId;
        this.toolName = toolName;
        this.toolCategory = toolCategory;
        this.inputSummary = inputSummary;
        this.llmModel = llmModel;
        this.promptVersion = promptVersion;
        this.state = ActionState.PROPOSED;
    }

    public static AgentAction proposed(UUID merchantId, String mode, UUID conversationId, String correlationId,
                                       String toolName, String toolCategory, String inputSummary,
                                       String llmModel, String promptVersion) {
        return new AgentAction(merchantId, mode, conversationId, correlationId, toolName, toolCategory,
                inputSummary, llmModel, promptVersion);
    }

    // ── State advancement ───────────────────────────────────────────────────────────────

    /** The arguments passed the schema and were resolved against server-side facts. */
    public void markValidated(String resolvedInputSummary) {
        this.state = ActionState.VALIDATED;
        this.inputSummary = resolvedInputSummary;
    }

    public void markRefused(PolicyDecision decision, String reasonCode, String reason, Long budgetRemainingMinor) {
        this.state = ActionState.REFUSED;
        this.policyDecision = decision;
        this.failureCode = reasonCode;
        this.failureMessage = reason;
        this.budgetRemainingMinor = budgetRemainingMinor;
        this.completedAt = Instant.now();
    }

    public void markApprovalRequired(UUID approvalId, String reasonCode, String reason, Long budgetRemainingMinor) {
        this.state = ActionState.APPROVAL_REQUIRED;
        this.policyDecision = PolicyDecision.REQUIRES_APPROVAL;
        this.approvalId = approvalId;
        this.failureCode = reasonCode;
        this.failureMessage = reason;
        this.budgetRemainingMinor = budgetRemainingMinor;
    }

    /** Written before the first platform call, so an interrupted action is visible afterwards. */
    public void markExecuting(Long budgetRemainingMinor) {
        this.state = ActionState.EXECUTING;
        this.policyDecision = PolicyDecision.PERMIT;
        if (budgetRemainingMinor != null) {
            this.budgetRemainingMinor = budgetRemainingMinor;
        }
        // A previously-refused reason must not survive a later permit; leaving it would make
        // an executed action look like it had also been refused.
        this.failureCode = null;
        this.failureMessage = null;
    }

    public void markExecuted() {
        this.state = ActionState.EXECUTED;
        this.completedAt = Instant.now();
    }

    public void markFailed(String failureCode, String failureMessage) {
        this.state = ActionState.FAILED;
        this.failureCode = failureCode;
        this.failureMessage = truncate(failureMessage);
        this.completedAt = Instant.now();
    }

    public void attachCheckout(UUID checkoutId) {
        this.checkoutId = checkoutId;
    }

    public void attachPayment(UUID paymentId) {
        this.paymentId = paymentId;
    }

    // ── Steps ───────────────────────────────────────────────────────────────────────────

    /** Opens a step and returns it, already marked in flight. The caller completes it. */
    public AgentActionStep beginStep(String operation, String idempotencyKey, String correlationId) {
        AgentActionStep step = AgentActionStep.inFlight(this, steps.size() + 1, operation, idempotencyKey,
                correlationId);
        steps.add(step);
        return step;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1000 ? value : value.substring(0, 997) + "...";
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

    public UUID getConversationId() {
        return conversationId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getToolName() {
        return toolName;
    }

    public String getToolCategory() {
        return toolCategory;
    }

    public String getInputSummary() {
        return inputSummary;
    }

    public ActionState getState() {
        return state;
    }

    public PolicyDecision getPolicyDecision() {
        return policyDecision;
    }

    public UUID getApprovalId() {
        return approvalId;
    }

    public UUID getCheckoutId() {
        return checkoutId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public Long getBudgetRemainingMinor() {
        return budgetRemainingMinor;
    }

    public String getLlmModel() {
        return llmModel;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public List<AgentActionStep> getSteps() {
        return List.copyOf(steps);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
