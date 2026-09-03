package com.paymentflow.agentic.action;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Reads over the step log, and in particular over the derived idempotency key.
 *
 * <p>{@link #findByIdempotencyKeyOrderByCreatedAtAsc} is what turns "the agent cannot
 * double-charge" from a claim about the platform's behaviour into something this service can
 * <em>evidence</em>. A second step carrying a key an earlier step already used successfully is
 * a replay, and it is recorded as {@link StepState#REPLAYED} on that basis — the platform sends
 * no header saying so, and inferring it from the response body would be guesswork.
 *
 * <p><b>Why this one finder is not merchant-scoped.</b> Every other repository in this service
 * is scoped by {@code (merchantId, mode)} without exception. A step carries neither column, and
 * adding them would be denormalisation for its own sake: the key is a SHA-256 over a tuple that
 * already contains the conversation id, and a conversation belongs to exactly one merchant. Two
 * merchants cannot derive the same key without a hash collision, so the key <em>is</em> the
 * scope.
 */
public interface AgentActionStepRepository extends JpaRepository<AgentActionStep, UUID> {

    /** Every step ever attempted under one derived key, oldest first. */
    List<AgentActionStep> findByIdempotencyKeyOrderByCreatedAtAsc(String idempotencyKey);

    /**
     * Whether this key has already been used on a call the platform accepted.
     *
     * <p>{@code SUCCEEDED} and {@code REPLAYED} both count: the question is whether the
     * operation has already happened at the platform, and a replay means it had happened even
     * earlier. A {@code FAILED} or {@code IN_FLIGHT} step does not count, because neither
     * establishes that the platform did anything — which is exactly the case where re-sending
     * the same key is the correct move.
     */
    boolean existsByIdempotencyKeyAndStateIn(String idempotencyKey, List<StepState> states);
}
