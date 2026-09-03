package com.paymentflow.agentic.approval;

import com.paymentflow.agentic.config.AgenticProperties;
import com.paymentflow.agentic.policy.PolicyDecision;
import com.paymentflow.agentic.policy.PolicyOperation;
import com.paymentflow.agentic.policy.PolicyRequest;
import com.paymentflow.agentic.policy.PolicyRule;
import com.paymentflow.agentic.policy.PolicyVerdict;
import com.paymentflow.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.paymentflow.agentic.approval.ApprovalFixtures.ACTION_ID;
import static com.paymentflow.agentic.approval.ApprovalFixtures.CONVERSATION_ID;
import static com.paymentflow.agentic.approval.ApprovalFixtures.CURRENCY;
import static com.paymentflow.agentic.approval.ApprovalFixtures.MERCHANT_ID;
import static com.paymentflow.agentic.approval.ApprovalFixtures.MODE;
import static com.paymentflow.agentic.approval.ApprovalFixtures.PAYMENT_ID;
import static com.paymentflow.agentic.approval.ApprovalFixtures.REFUND_AMOUNT;
import static com.paymentflow.agentic.approval.ApprovalFixtures.T0;
import static com.paymentflow.agentic.approval.ApprovalFixtures.refundBinding;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ApprovalService}'s own behaviour: the TTL it applies, the idempotency of a request,
 * and the fact that expiry is written down when it is noticed rather than only reported.
 *
 * <p>The repository is a stub rather than a real one because none of what is being asserted
 * here is about SQL. The aggregate's rules are covered in {@link ApprovalTest}; what this
 * class adds is the wiring around them.
 */
class ApprovalServiceTest {

    private static final int TTL_MINUTES = 30;

    private ApprovalRepository repository;
    private ApprovalFixtures.MutableClock clock;
    private ApprovalService service;
    private final List<Approval> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repository = mock(ApprovalRepository.class);
        clock = new ApprovalFixtures.MutableClock(T0);
        service = new ApprovalService(repository, properties(), clock);
        saved.clear();
        when(repository.save(any(Approval.class))).thenAnswer(invocation -> {
            Approval approval = invocation.getArgument(0);
            saved.add(approval);
            return approval;
        });
    }

    @Test
    @DisplayName("a requested approval takes its expiry from the configured TTL")
    void requestAppliesConfiguredTtl() {
        when(repository.findByAgentActionId(ACTION_ID)).thenReturn(Optional.empty());

        Approval approval = service.request(ACTION_ID, refundPolicyRequest(), requiresApprovalVerdict());

        assertThat(approval.getExpiresAt()).isEqualTo(T0.plus(Duration.ofMinutes(TTL_MINUTES)));
        assertThat(approval.getState()).isEqualTo(ApprovalState.PENDING);
    }

    @Test
    @DisplayName("the binding is taken from the same request the policy engine decided on")
    void requestBindsToThePolicyRequest() {
        when(repository.findByAgentActionId(ACTION_ID)).thenReturn(Optional.empty());

        Approval approval = service.request(ACTION_ID, refundPolicyRequest(), requiresApprovalVerdict());

        assertThat(approval.binding()).isEqualTo(refundBinding());
        assertThat(approval.getConversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(approval.getToolName()).isEqualTo("request_refund");
    }

    @Test
    @DisplayName("a second request for the same action returns the first, rather than opening a second")
    void requestIsIdempotentPerAction() {
        Approval existing = ApprovalFixtures.pendingRefund(Duration.ofMinutes(TTL_MINUTES));
        when(repository.findByAgentActionId(ACTION_ID)).thenReturn(Optional.of(existing));

        Approval approval = service.request(ACTION_ID, refundPolicyRequest(), requiresApprovalVerdict());

        assertThat(approval).isSameAs(existing);
        assertThat(saved).as("no second approval is written").isEmpty();
    }

    @Test
    @DisplayName("expiry noticed on read is persisted, not merely reported")
    void expiryOnReadIsWrittenDown() {
        Approval approval = ApprovalFixtures.pendingRefund(Duration.ofMinutes(TTL_MINUTES));
        when(repository.findByIdAndMerchantIdAndMode(any(), eq(MERCHANT_ID), eq(MODE)))
                .thenReturn(Optional.of(approval));
        clock.advance(Duration.ofMinutes(TTL_MINUTES + 1));

        Approval loaded = service.require(MERCHANT_ID, MODE, UUID.randomUUID());

        assertThat(loaded.getState()).isEqualTo(ApprovalState.EXPIRED);
        assertThat(saved).containsExactly(approval);
    }

    @Test
    @DisplayName("an approval belonging to another merchant is a 404, not a mismatch")
    void crossTenantApprovalIsNotFound() {
        when(repository.findByIdAndMerchantIdAndMode(any(), any(), any())).thenReturn(Optional.empty());

        UUID approvalId = UUID.randomUUID();
        assertThatThrownBy(() -> service.require(MERCHANT_ID, MODE, approvalId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("redeeming through the service consumes the approval and writes it")
    void redeemConsumesAndPersists() {
        Approval approval = ApprovalFixtures.pendingRefund(Duration.ofMinutes(TTL_MINUTES));
        approval.approve("ops", T0);
        when(repository.findByIdAndMerchantIdAndMode(any(), eq(MERCHANT_ID), eq(MODE)))
                .thenReturn(Optional.of(approval));

        Approval redeemed = service.redeem(MERCHANT_ID, MODE, UUID.randomUUID(), refundBinding());

        assertThat(redeemed.getState()).isEqualTo(ApprovalState.CONSUMED);
        assertThat(saved).contains(approval);
    }

    @Test
    @DisplayName("a pending listing hides entries that have quietly expired")
    void pendingListingAgesOutExpiredEntries() {
        Approval fresh = Approval.request(UUID.randomUUID(), CONVERSATION_ID, "request_refund", refundBinding(),
                "reason", T0.plus(Duration.ofMinutes(60)));
        Approval stale = ApprovalFixtures.pendingRefund(Duration.ofMinutes(5));
        when(repository.findByMerchantIdAndModeAndStateOrderByCreatedAtDesc(
                eq(MERCHANT_ID), eq(MODE), eq(ApprovalState.PENDING), any()))
                .thenReturn(List.of(fresh, stale));
        clock.advance(Duration.ofMinutes(10));

        List<Approval> pending = service.findPending(MERCHANT_ID, MODE);

        assertThat(pending).containsExactly(fresh);
        assertThat(stale.getState()).isEqualTo(ApprovalState.EXPIRED);
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────────────

    private static AgenticProperties properties() {
        return new AgenticProperties(
                new AgenticProperties.Platform("http://localhost:8080", "sk_test_fixture", 2000, 10000),
                new AgenticProperties.Policy("2026-08-20.1", CURRENCY, 5_000_000L, 10_000_000L, 100_000L,
                        2_000_000L, 5_000_000L, 60, TTL_MINUTES),
                new AgenticProperties.Checkout(30, 20),
                new AgenticProperties.Llm("anthropic", "https://example.invalid", "", "model", 2048, 0.2, 30000, 8, 120000, "", ""),
                new AgenticProperties.Razorpay(false, "https://example.invalid", "", "", 2000, 8000, "decline"),
                new AgenticProperties.Demo("", false));
    }

    private static PolicyRequest refundPolicyRequest() {
        return new PolicyRequest(
                new PolicyRequest.Actor(MERCHANT_ID, MODE, "session-1", "merchant:11111111/session-1"),
                new PolicyRequest.Conversation(CONVERSATION_ID, true, 0, 0, 1),
                "request_refund",
                PolicyOperation.REFUND_CREATE,
                new PolicyRequest.Target(null, null, PAYMENT_ID, REFUND_AMOUNT, CURRENCY));
    }

    private static PolicyVerdict requiresApprovalVerdict() {
        return new PolicyVerdict(PolicyDecision.REQUIRES_APPROVAL, PolicyRule.REFUND_APPROVAL_THRESHOLD,
                "Amount 250000 is above the 100000 approval threshold and requires human approval.",
                "2026-08-20.1", 5_000_000L);
    }
}
