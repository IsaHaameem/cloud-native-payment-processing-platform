package com.paymentflow.agentic.policy;

import com.paymentflow.agentic.action.AgentAction;
import com.paymentflow.agentic.action.AgentActionRepository;
import com.paymentflow.common.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Writes the policy decision down. Separate from {@link PolicyEngine} on purpose.
 *
 * <p>Keeping the evaluator pure and the recorder separate is what lets the engine be tested
 * against a table of amounts with no database in sight, and it removes the one way an audit
 * write could change a decision: there is no code path in which persisting a verdict can
 * alter it.
 *
 * <h2>The ordering guarantee</h2>
 *
 * <p>The contract is that <b>the decision is durable before any financial call is made</b>.
 * This class joins the caller's transaction rather than opening its own, which means the
 * caller is responsible for committing before it reaches the network — and that is the right
 * division, because only the caller knows where its own commit boundary is.
 *
 * <p>{@code REQUIRES_NEW} was considered and rejected. The decision carries a foreign key to
 * {@code agent_actions}, and an action created in an outer transaction that has not yet
 * committed is not visible to a new one; the write would fail the constraint. A separate
 * transaction would therefore trade a guarantee that holds for one that fails precisely when
 * an action is new, which is every time.
 */
@Service
public class PolicyDecisionLog {

    private static final Logger log = LoggerFactory.getLogger(PolicyDecisionLog.class);

    private final PolicyDecisionRepository repository;
    private final AgentActionRepository actionRepository;
    private final Clock clock;

    public PolicyDecisionLog(PolicyDecisionRepository repository, AgentActionRepository actionRepository,
                             Clock clock) {
        this.repository = repository;
        this.actionRepository = actionRepository;
        this.clock = clock;
    }

    /**
     * Records an evaluation against an action identified by id.
     *
     * <p>Added in Phase 11, and not merely for convenience. The runtime commits each action in
     * its own transaction before evaluating policy — so the {@link AgentAction} it holds is
     * detached, and handing a detached entity to a {@code @ManyToOne} is how an audit write
     * starts failing intermittently. Re-loading it here keeps the association managed and the
     * write boring.
     */
    @Transactional
    public PolicyDecisionRecord record(UUID agentActionId, PolicyRequest request, PolicyVerdict verdict) {
        AgentAction action = actionRepository.findById(agentActionId)
                .orElseThrow(() -> ResourceNotFoundException.of("AgentAction", agentActionId));
        return record(action, request, verdict);
    }

    /**
     * Records one evaluation against one action.
     *
     * <p>Called for every verdict, not only for refusals. A trail with entries only where
     * something was blocked cannot tell "this was allowed" apart from "this was never
     * checked", and the second is the failure the trail exists to detect.
     */
    @Transactional
    public PolicyDecisionRecord record(AgentAction action, PolicyRequest request, PolicyVerdict verdict) {
        PolicyDecisionRecord saved = repository.save(
                PolicyDecisionRecord.of(action, request, verdict, clock.instant()));

        // At INFO because a refusal is an event an operator should see without turning
        // anything on, and the fields are all server-derived — there is nothing here that
        // could carry a credential or model text.
        log.info("policy decision tool={} operation={} decision={} rule={} reason_code={} "
                        + "policy_version={} budget_remaining_minor={}",
                request.toolName(), request.operation(), verdict.decision(), verdict.ruleId(),
                verdict.reasonCode(), verdict.policyVersion(), verdict.budgetRemainingMinor());
        return saved;
    }

    /** Every evaluation of one action, oldest first. The audit read behind the demo's gating proof. */
    @Transactional(readOnly = true)
    public List<PolicyDecisionRecord> findByAction(UUID agentActionId) {
        return repository.findByActionIdOrderByEvaluatedAtAsc(agentActionId);
    }
}
