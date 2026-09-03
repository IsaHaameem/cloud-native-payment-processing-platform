package com.paymentflow.agentic.approval;

import com.paymentflow.agentic.config.AgenticProperties;
import com.paymentflow.agentic.policy.PolicyRequest;
import com.paymentflow.agentic.policy.PolicyVerdict;
import com.paymentflow.common.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Opens, decides and spends approvals. The human gate in front of the money.
 *
 * <p><b>Nothing in this class executes a financial operation, and nothing in it can be made
 * to.</b> It has no platform client, no provider client and no payment code on its
 * dependencies at all. The most it can do is hand back a consumed approval; whether anything
 * happens next is the caller's problem, and the caller cannot get that far without coming
 * through {@link #redeem}.
 *
 * <p>Expiry is evaluated on read and persisted, the same way {@code CheckoutService} handles
 * checkout expiry. A caller therefore never sees a {@code PENDING} approval that is really
 * dead, and this service runs no scheduled job whose only purpose is to write a state nobody
 * has asked about yet.
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    /** How many pending approvals a listing returns. A queue longer than this is an incident, not a page. */
    private static final int MAX_PENDING_LISTED = 50;

    private final ApprovalRepository approvalRepository;
    private final AgenticProperties properties;
    private final Clock clock;

    public ApprovalService(ApprovalRepository approvalRepository, AgenticProperties properties, Clock clock) {
        this.approvalRepository = approvalRepository;
        this.properties = properties;
        this.clock = clock;
    }

    // ── Requesting ──────────────────────────────────────────────────────────────────────

    /**
     * Opens an approval request for an action the policy engine held at
     * {@code REQUIRES_APPROVAL}.
     *
     * <p>The binding is taken from the same {@link PolicyRequest} the engine decided on, so
     * the thing the human is shown and the thing the engine evaluated are the same set of
     * facts rather than two independently-assembled descriptions of it.
     *
     * <p><b>Idempotent per action.</b> A second request for an action that already has one
     * returns the existing approval rather than creating a second. Two live approvals for one
     * action would make "who authorised this" ambiguous, and the schema refuses it anyway
     * ({@code uq_approvals_action}) — returning the original turns a race into a no-op instead
     * of a constraint violation.
     */
    @Transactional
    public Approval request(UUID agentActionId, PolicyRequest request, PolicyVerdict verdict) {
        Optional<Approval> existing = approvalRepository.findByAgentActionId(agentActionId);
        if (existing.isPresent()) {
            return refreshExpiry(existing.get());
        }

        Instant expiresAt = clock.instant()
                .plus(Duration.ofMinutes(properties.policy().approvalTtlMinutes()));
        Approval approval = Approval.request(agentActionId, request.conversation().id(), request.toolName(),
                ApprovalBinding.of(request), verdict.reason(), expiresAt);
        Approval saved = approvalRepository.save(approval);

        log.info("approval requested id={} action={} tool={} operation={} amount_minor={} currency={} expires_at={}",
                saved.getId(), agentActionId, saved.getToolName(), saved.getRequestedOperation(),
                saved.getAmountMinor(), saved.getCurrency(), saved.getExpiresAt());
        return saved;
    }

    // ── Deciding ────────────────────────────────────────────────────────────────────────

    /** A human said yes. The action still does not execute until it is redeemed. */
    @Transactional
    public Approval approve(UUID merchantId, String mode, UUID approvalId, String decidedBy) {
        Approval approval = require(merchantId, mode, approvalId);
        approval.approve(decidedBy, clock.instant());
        log.info("approval granted id={} action={} decided_by={}", approvalId, approval.getAgentActionId(),
                decidedBy);
        return approvalRepository.save(approval);
    }

    /** A human said no. Terminal; the action it belongs to is refused. */
    @Transactional
    public Approval deny(UUID merchantId, String mode, UUID approvalId, String decidedBy, String reason) {
        Approval approval = require(merchantId, mode, approvalId);
        approval.deny(decidedBy, reason, clock.instant());
        log.info("approval denied id={} action={} decided_by={}", approvalId, approval.getAgentActionId(),
                decidedBy);
        return approvalRepository.save(approval);
    }

    // ── Spending ────────────────────────────────────────────────────────────────────────

    /**
     * The gate a money action must pass before it may execute under an approval.
     *
     * <p>{@code actual} must be derived from server-side facts <em>at the moment of
     * execution</em> — the checkout's own total, the payment's own currency — and never from
     * the caller's request or the model's arguments. Handing this method the binding the
     * approval was created from would make the check compare a value against itself, which is
     * the one way to make an approval workflow look complete and enforce nothing.
     *
     * <p>Succeeds at most once per approval: the approval leaves in
     * {@link ApprovalState#CONSUMED}, so a retry finds nothing to spend.
     */
    @Transactional
    public Approval redeem(UUID merchantId, String mode, UUID approvalId, ApprovalBinding actual) {
        Approval approval = require(merchantId, mode, approvalId);
        approval.redeem(actual, clock.instant());
        log.info("approval consumed id={} action={} operation={} amount_minor={}", approvalId,
                approval.getAgentActionId(), approval.getRequestedOperation(), approval.getAmountMinor());
        return approvalRepository.save(approval);
    }

    // ── Reads ───────────────────────────────────────────────────────────────────────────

    /** Loads an approval, expiring it first if its time has passed. */
    @Transactional
    public Approval require(UUID merchantId, String mode, UUID approvalId) {
        Approval approval = approvalRepository.findByIdAndMerchantIdAndMode(approvalId, merchantId, mode)
                .orElseThrow(() -> ResourceNotFoundException.of("Approval", approvalId));
        return refreshExpiry(approval);
    }

    @Transactional
    public Optional<Approval> findByAction(UUID agentActionId) {
        return approvalRepository.findByAgentActionId(agentActionId).map(this::refreshExpiry);
    }

    /** The approval queue a human works through. Expired entries are aged out as they are read. */
    @Transactional
    public List<Approval> findPending(UUID merchantId, String mode) {
        return approvalRepository
                .findByMerchantIdAndModeAndStateOrderByCreatedAtDesc(merchantId, mode, ApprovalState.PENDING,
                        PageRequest.of(0, MAX_PENDING_LISTED))
                .stream()
                .map(this::refreshExpiry)
                .filter(approval -> approval.getState() == ApprovalState.PENDING)
                .toList();
    }

    private Approval refreshExpiry(Approval approval) {
        ApprovalState before = approval.getState();
        approval.expireIfDue(clock.instant());
        if (approval.getState() != before) {
            log.info("approval expired id={} action={} expires_at={}", approval.getId(),
                    approval.getAgentActionId(), approval.getExpiresAt());
            return approvalRepository.save(approval);
        }
        return approval;
    }
}
