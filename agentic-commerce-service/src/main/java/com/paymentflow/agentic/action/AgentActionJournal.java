package com.paymentflow.agentic.action;

import com.paymentflow.common.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Records what a money tool actually did to the platform, one step per call.
 *
 * <p>A composite money tool makes several platform calls — create, then authorize, then
 * capture — and only the tool knows how many. So the tool records its own steps, through this
 * journal, rather than the orchestrator guessing at them afterwards. What the orchestrator owns
 * is the <em>action</em>; what the tool owns is the sequence of platform operations inside it.
 *
 * <h2>Every write is its own transaction, and that is the point</h2>
 *
 * <p>{@link Propagation#REQUIRES_NEW} on every method here. A step is written <b>before</b> the
 * HTTP call it describes and completed after, and both writes have to survive whatever happens
 * to the caller's transaction — including a rollback. The failure this prevents is the serious
 * one: a payment that succeeded at the platform while the surrounding transaction rolled back,
 * leaving this service with no record that it had ever called. An audit trail that disappears
 * exactly when something went wrong is worse than none, because it reads as "nothing happened".
 *
 * <p>Unlike {@code PolicyDecisionLog}, this can safely open its own transaction: a step's
 * foreign key points at an action that the orchestrator has already committed before any
 * platform call is attempted.
 */
@Service
public class AgentActionJournal {

    private static final Logger log = LoggerFactory.getLogger(AgentActionJournal.class);

    /** The step states that establish the platform actually did the thing. */
    private static final List<StepState> COMPLETED_AT_PLATFORM =
            List.of(StepState.SUCCEEDED, StepState.REPLAYED);

    private final AgentActionRepository actionRepository;
    private final AgentActionStepRepository stepRepository;

    public AgentActionJournal(AgentActionRepository actionRepository, AgentActionStepRepository stepRepository) {
        this.actionRepository = actionRepository;
        this.stepRepository = stepRepository;
    }

    // ── Actions ─────────────────────────────────────────────────────────────────────────

    /**
     * Opens an action in {@link ActionState#PROPOSED} and commits it.
     *
     * <p>Committed before anything is resolved, evaluated or executed, which is the ordering
     * the whole trail depends on: a tool call the model made is recorded even if every later
     * stage refuses it. An action log written only for the calls that succeeded would be unable
     * to show a refusal, which is the one thing a reviewer most wants to see.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AgentAction propose(UUID merchantId, String mode, UUID conversationId, String correlationId,
                               String toolName, String toolCategory, String inputSummary, String llmModel,
                               String promptVersion) {
        AgentAction action = AgentAction.proposed(merchantId, mode, conversationId, correlationId, toolName,
                toolCategory, inputSummary, llmModel, promptVersion);
        AgentAction saved = actionRepository.save(action);
        log.info("agent action proposed id={} tool={} conversation={} correlation_id={}",
                saved.getId(), toolName, conversationId, correlationId);
        return saved;
    }

    /** The arguments satisfied the schema and were resolved against server-side facts. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void validated(UUID actionId, String resolvedInputSummary, UUID checkoutId, UUID paymentId) {
        AgentAction action = requireAction(actionId);
        action.markValidated(resolvedInputSummary);
        if (checkoutId != null) {
            action.attachCheckout(checkoutId);
        }
        if (paymentId != null) {
            action.attachPayment(paymentId);
        }
        actionRepository.save(action);
    }

    /**
     * The policy engine refused, or an approval was denied. Terminal.
     *
     * <p>Its own transaction, so a refusal is durable even if the turn then fails for an
     * unrelated reason. A refusal that vanished on rollback would read as an action that was
     * never attempted.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refused(UUID actionId, com.paymentflow.agentic.policy.PolicyDecision decision,
                        String reasonCode, String reason, Long budgetRemainingMinor) {
        AgentAction action = requireAction(actionId);
        action.markRefused(decision, reasonCode, reason, budgetRemainingMinor);
        actionRepository.save(action);
        log.info("agent action refused id={} tool={} reason_code={}", actionId, action.getToolName(),
                reasonCode);
    }

    /** Policy requires a human. <b>Nothing financial has been attempted, and nothing will be from here.</b> */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void approvalRequired(UUID actionId, UUID approvalId, String reasonCode, String reason,
                                 Long budgetRemainingMinor) {
        AgentAction action = requireAction(actionId);
        action.markApprovalRequired(approvalId, reasonCode, reason, budgetRemainingMinor);
        actionRepository.save(action);
        log.info("agent action awaiting approval id={} tool={} approval={}", actionId,
                action.getToolName(), approvalId);
    }

    /**
     * Written immediately before the first platform call.
     *
     * <p>Committed in its own transaction so that a crash mid-execution leaves an action in
     * {@code EXECUTING} rather than in {@code VALIDATED} — the difference between a trail that
     * says "an attempt was in flight" and one that says "nothing was ever tried".
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void executing(UUID actionId, Long budgetRemainingMinor) {
        AgentAction action = requireAction(actionId);
        action.markExecuting(budgetRemainingMinor);
        actionRepository.save(action);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void executed(UUID actionId, UUID paymentId) {
        AgentAction action = requireAction(actionId);
        if (paymentId != null) {
            action.attachPayment(paymentId);
        }
        action.markExecuted();
        actionRepository.save(action);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(UUID actionId, String failureCode, String failureMessage) {
        AgentAction action = requireAction(actionId);
        action.markFailed(failureCode, Redactor.redactText(failureMessage));
        actionRepository.save(action);
        log.info("agent action failed id={} tool={} failure_code={}", actionId, action.getToolName(),
                failureCode);
    }

    /** The action trail for one conversation, newest first. What the demo's audit view reads. */
    @Transactional(readOnly = true)
    public List<AgentAction> actionsForConversation(UUID conversationId) {
        return actionRepository.findByConversationIdOrderByCreatedAtDesc(conversationId);
    }

    @Transactional(readOnly = true)
    public AgentAction requireAction(UUID merchantId, String mode, UUID actionId) {
        return actionRepository.findByIdAndMerchantIdAndMode(actionId, merchantId, mode)
                .orElseThrow(() -> ResourceNotFoundException.of("AgentAction", actionId));
    }

    // ── Steps ───────────────────────────────────────────────────────────────────────────

    /**
     * Opens a step and commits it as {@code IN_FLIGHT} before the call it describes is made.
     *
     * <p>The ordering is the whole value. A crash between this write and the response leaves a
     * row saying an attempt was in flight under a known idempotency key, which is recoverable.
     * A log written only on success would leave nothing at all — the failure mode that makes an
     * audit trail worthless at precisely the moment it is needed.
     *
     * @return the id of the step, which the caller completes
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID beginStep(UUID agentActionId, String operation, String idempotencyKey, String correlationId) {
        AgentAction action = requireAction(agentActionId);
        int sequenceNo = action.getSteps().size() + 1;
        action.beginStep(operation, idempotencyKey, correlationId);

        // The id is read off the SAVED aggregate, not off the step object created above, and
        // the flush is required. Two things conspire here, and both were found by running the
        // service rather than by any mocked-repository test:
        //
        //   * save() on an entity with a non-null id merges, and merge returns a NEW managed
        //     instance — the step hanging off the original object stays detached forever;
        //   * without a flush the cascade insert has not run, so no generated id exists yet.
        //
        // Get either wrong and beginStep returns null, every later stepSucceeded/stepFailed
        // call throws "The given id must not be null", and the step rows silently never
        // complete. Those rows are the evidence the no-double-charge claim rests on, so this
        // failing quietly would have been the worst kind of bug in this service.
        AgentAction saved = actionRepository.saveAndFlush(action);
        UUID stepId = saved.getSteps().stream()
                .filter(candidate -> candidate.getSequenceNo() == sequenceNo)
                .map(AgentActionStep::getId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Step " + sequenceNo + " was not persisted for action " + agentActionId));

        log.info("agent step opened action={} operation={} idempotency_key={} correlation_id={}",
                agentActionId, operation, idempotencyKey, correlationId);
        return stepId;
    }

    /**
     * Completes a step the platform accepted.
     *
     * <p>{@code replayed} is <b>not</b> read from the response. The platform sends no header
     * saying "this was a replay", so it is established here, from this service's own records:
     * if a step already completed at the platform under this key, this one is a replay of it.
     * That is what makes the no-double-charge claim evidenced by two rows rather than asserted.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void stepSucceeded(UUID stepId, int httpStatus, String requestId, UUID paymentId,
                              String providerReference) {
        AgentActionStep step = requireStep(stepId);
        boolean replayed = wasAlreadyCompletedAtPlatform(step);
        step.succeeded(httpStatus, requestId, paymentId, replayed);
        if (providerReference != null) {
            step.attachProviderReference(providerReference);
        }
        stepRepository.save(step);

        log.info("agent step {} action={} operation={} status={} request_id={} payment={}",
                replayed ? "replayed" : "succeeded", step.getAgentActionId(), step.getOperation(),
                httpStatus, requestId, paymentId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void stepFailed(UUID stepId, Integer httpStatus, String requestId, String failureCode,
                           String failureMessage) {
        AgentActionStep step = requireStep(stepId);
        step.failed(httpStatus, requestId, failureCode, failureMessage);
        stepRepository.save(step);

        log.info("agent step failed action={} operation={} status={} request_id={} failure_code={}",
                step.getAgentActionId(), step.getOperation(), httpStatus, requestId, failureCode);
    }

    /**
     * Whether this derived key has already been spent on a call the platform accepted.
     *
     * <p>Read by the money tools before they call, so a repeated tool invocation can say
     * plainly that it is replaying rather than charging — and by tests, which is how "the same
     * logical payment retried reaches the replay mechanism" becomes a checkable claim.
     */
    @Transactional(readOnly = true)
    public boolean isAlreadyCompleted(String idempotencyKey) {
        return idempotencyKey != null
                && stepRepository.existsByIdempotencyKeyAndStateIn(idempotencyKey, COMPLETED_AT_PLATFORM);
    }

    /** Every step ever attempted under one derived key, oldest first. The replay evidence itself. */
    @Transactional(readOnly = true)
    public List<AgentActionStep> stepsForKey(String idempotencyKey) {
        return stepRepository.findByIdempotencyKeyOrderByCreatedAtAsc(idempotencyKey);
    }

    private boolean wasAlreadyCompletedAtPlatform(AgentActionStep step) {
        return step.getIdempotencyKey() != null
                && stepRepository.findByIdempotencyKeyOrderByCreatedAtAsc(step.getIdempotencyKey()).stream()
                .anyMatch(earlier -> !earlier.getId().equals(step.getId())
                        && COMPLETED_AT_PLATFORM.contains(earlier.getState()));
    }

    private AgentAction requireAction(UUID agentActionId) {
        return actionRepository.findById(agentActionId)
                .orElseThrow(() -> ResourceNotFoundException.of("AgentAction", agentActionId));
    }

    private AgentActionStep requireStep(UUID stepId) {
        return stepRepository.findById(stepId)
                .orElseThrow(() -> ResourceNotFoundException.of("AgentActionStep", stepId));
    }
}
