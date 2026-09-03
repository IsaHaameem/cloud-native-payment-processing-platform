package com.paymentflow.agentic.policy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Read access to the append-only decision log.
 *
 * <p>Unusually for this service, the finders here are keyed by action rather than by
 * {@code (merchantId, mode)} — and that is safe rather than an oversight, because a decision
 * is only ever reached through an action that was itself loaded through a merchant-scoped
 * finder. There is no path that starts at a decision id.
 *
 * <p>No {@code delete} is exposed and none is used. The log is evidence; a repository that
 * offers to remove a refusal is one where a refusal can quietly stop existing.
 */
public interface PolicyDecisionRepository extends JpaRepository<PolicyDecisionRecord, UUID> {

    /**
     * Every evaluation of one action, oldest first — so an action that was refused pending
     * approval and then permitted reads in the order the two things happened.
     */
    List<PolicyDecisionRecord> findByActionIdOrderByEvaluatedAtAsc(UUID agentActionId);

    List<PolicyDecisionRecord> findByActionIdInOrderByEvaluatedAtAsc(List<UUID> agentActionIds);
}
