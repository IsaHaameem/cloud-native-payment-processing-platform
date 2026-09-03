package com.paymentflow.agentic.policy;

import com.paymentflow.agentic.action.AgentAction;
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

import java.time.Instant;
import java.util.UUID;

/**
 * One evaluation of one action by the policy engine. Append-only; nothing here is ever
 * updated or deleted.
 *
 * <p><b>Why this is a table and not four more columns on {@code agent_actions}.</b> An action
 * that stops at {@code REQUIRES_APPROVAL} is evaluated a <em>second</em> time when the
 * approval is granted, and both evaluations are part of the record. Flattening them onto the
 * action would mean the second overwrote the first, and the row would then claim the action
 * had always been permitted — erasing exactly the evidence that a human was asked.
 *
 * <p>The association to {@link AgentAction} is a real {@code @ManyToOne} rather than a loose
 * UUID column so that Hibernate orders the two inserts itself. A decision flushed ahead of the
 * action it describes would fail the foreign key, and it would fail intermittently, which is
 * the worst way for an audit write to break.
 *
 * <p>Every string field here originates in this service. {@code reason} is assembled by
 * {@link PolicyEngine} from server-side numbers, so no part of this row can carry model
 * output.
 */
@Entity
@Table(name = "policy_decisions")
public class PolicyDecisionRecord {

    /** Mirrors {@code reason varchar(500)}; enforced here so an over-long reason truncates rather than throwing. */
    private static final int MAX_REASON_LENGTH = 500;

    /** Mirrors {@code resource varchar(128)}. */
    private static final int MAX_RESOURCE_LENGTH = 128;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "agent_action_id", nullable = false, updatable = false)
    private AgentAction action;

    /**
     * The threshold set in force when this was decided. Without it the trail would say what
     * was decided but not what the rules were at the time — the half that matters when someone
     * asks why the same refund was permitted last month and refused today.
     */
    @Column(name = "policy_version", nullable = false, updatable = false, length = 32)
    private String policyVersion;

    @Column(name = "rule_id", nullable = false, updatable = false, length = 64)
    private String ruleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 24)
    private PolicyDecision decision;

    @Column(name = "reason_code", nullable = false, updatable = false, length = 64)
    private String reasonCode;

    @Column(nullable = false, updatable = false, length = 500)
    private String reason;

    @Column(nullable = false, updatable = false, length = 128)
    private String actor;

    @Column(name = "tool_name", nullable = false, updatable = false, length = 64)
    private String toolName;

    /** The checkout or payment this decision was about, prefixed so the two are never confused. */
    @Column(updatable = false, length = 128)
    private String resource;

    @Column(name = "evaluated_at", nullable = false, updatable = false)
    private Instant evaluatedAt;

    protected PolicyDecisionRecord() {
        // Required by JPA.
    }

    private PolicyDecisionRecord(AgentAction action, PolicyRequest request, PolicyVerdict verdict, Instant at) {
        this.action = action;
        this.policyVersion = verdict.policyVersion();
        this.ruleId = verdict.ruleId();
        this.decision = verdict.decision();
        this.reasonCode = verdict.reasonCode();
        this.reason = truncate(verdict.reason(), MAX_REASON_LENGTH);
        this.actor = request.actor().principal();
        this.toolName = request.toolName();
        this.resource = truncate(resourceOf(request), MAX_RESOURCE_LENGTH);
        this.evaluatedAt = at;
    }

    static PolicyDecisionRecord of(AgentAction action, PolicyRequest request, PolicyVerdict verdict, Instant at) {
        return new PolicyDecisionRecord(action, request, verdict, at);
    }

    /**
     * The subject of the decision, as a prefixed reference.
     *
     * <p>Prefixed rather than a bare UUID because a checkout id and a payment id are both
     * UUIDs, and a column that holds either without saying which is a column nobody can query
     * confidently later.
     */
    private static String resourceOf(PolicyRequest request) {
        PolicyRequest.Target target = request.target();
        if (target.checkoutId() != null) {
            return "checkout:" + target.checkoutId();
        }
        if (target.paymentId() != null) {
            return "payment:" + target.paymentId();
        }
        return null;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    // ── Accessors ───────────────────────────────────────────────────────────────────────

    public UUID getId() {
        return id;
    }

    public UUID getAgentActionId() {
        return action == null ? null : action.getId();
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public String getRuleId() {
        return ruleId;
    }

    public PolicyDecision getDecision() {
        return decision;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public String getReason() {
        return reason;
    }

    public String getActor() {
        return actor;
    }

    public String getToolName() {
        return toolName;
    }

    public String getResource() {
        return resource;
    }

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }
}
