package com.paymentflow.agentic.action;

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
 * One platform operation inside an action — the unit the <em>platform</em> is accountable for.
 *
 * <p>{@code idempotencyKey} is stored deliberately. It is what turns "the agent cannot
 * double-charge" from a claim about the platform into something this service can evidence: a
 * second step carrying the same key, in state {@link StepState#REPLAYED}, next to the first
 * one that actually charged.
 *
 * <p>{@code requestId} is the identifier the platform echoes back on every response (D168)
 * and the key every row of the merchant's own request log carries — so it, not the
 * correlation id, is what joins a step to {@code GET /v1/request_logs}.
 */
@Entity
@Table(name = "agent_action_steps")
public class AgentActionStep {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "agent_action_id", nullable = false, updatable = false)
    private AgentAction action;

    @Column(name = "sequence_no", nullable = false, updatable = false)
    private int sequenceNo;

    @Column(nullable = false, updatable = false, length = 64)
    private String operation;

    @Column(name = "idempotency_key", updatable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "correlation_id", nullable = false, updatable = false, length = 64)
    private String correlationId;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private StepState state;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "provider_reference", length = 128)
    private String providerReference;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_message", length = 1000)
    private String failureMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected AgentActionStep() {
        // Required by JPA.
    }

    private AgentActionStep(AgentAction action, int sequenceNo, String operation, String idempotencyKey,
                            String correlationId) {
        this.action = action;
        this.sequenceNo = sequenceNo;
        this.operation = operation;
        this.idempotencyKey = idempotencyKey;
        this.correlationId = correlationId;
        this.state = StepState.IN_FLIGHT;
    }

    static AgentActionStep inFlight(AgentAction action, int sequenceNo, String operation, String idempotencyKey,
                                    String correlationId) {
        return new AgentActionStep(action, sequenceNo, operation, idempotencyKey, correlationId);
    }

    public void succeeded(int httpStatus, String requestId, UUID paymentId, boolean replayed) {
        this.state = replayed ? StepState.REPLAYED : StepState.SUCCEEDED;
        this.httpStatus = httpStatus;
        this.requestId = requestId;
        this.paymentId = paymentId;
        this.completedAt = Instant.now();
    }

    public void failed(Integer httpStatus, String requestId, String failureCode, String failureMessage) {
        this.state = StepState.FAILED;
        this.httpStatus = httpStatus;
        this.requestId = requestId;
        this.failureCode = failureCode;
        this.failureMessage = truncate(failureMessage);
        this.completedAt = Instant.now();
    }

    public void attachProviderReference(String providerReference) {
        this.providerReference = providerReference;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1000 ? value : value.substring(0, 997) + "...";
    }

    public UUID getId() {
        return id;
    }

    /**
     * The action this step belongs to.
     *
     * <p>Exposed as an id rather than the association so a caller that only wants to log which
     * action a step came from does not trigger a lazy load of the whole aggregate.
     */
    public UUID getAgentActionId() {
        return action == null ? null : action.getId();
    }

    public int getSequenceNo() {
        return sequenceNo;
    }

    public String getOperation() {
        return operation;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getRequestId() {
        return requestId;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public StepState getState() {
        return state;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
