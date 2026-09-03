package com.paymentflow.agentic.web;

import com.paymentflow.agentic.action.ActionState;
import com.paymentflow.agentic.action.AgentActionRepository;
import com.paymentflow.agentic.approval.ApprovalRepository;
import com.paymentflow.agentic.approval.ApprovalState;
import com.paymentflow.agentic.conversation.ConversationRepository;
import com.paymentflow.agentic.policy.PolicyDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SummaryService} rolls up persisted counts for G-1. These pin the two things that could
 * go wrong quietly: the window defaulting, and that every count is scoped to the caller's own
 * {@code (merchant, mode)}.
 */
class SummaryServiceTest {

    private static final UUID MERCHANT = UUID.randomUUID();

    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final AgentActionRepository actions = mock(AgentActionRepository.class);
    private final ApprovalRepository approvals = mock(ApprovalRepository.class);
    private final SummaryService service = new SummaryService(conversations, actions, approvals);

    @Test
    @DisplayName("a null window becomes [epoch, now] and every count is merchant/mode scoped")
    void defaultWindowAndScoping() {
        stubZeros();

        SummaryService.SummaryView view = service.summarize(MERCHANT, "test", null, null);

        assertThat(view.window().from()).isEqualTo(Instant.EPOCH);
        assertThat(view.window().to()).isBetween(Instant.now().minusSeconds(5), Instant.now().plusSeconds(5));
        assertThat(view.source()).isEqualTo("persisted");

        ArgumentCaptor<UUID> merchant = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> mode = ArgumentCaptor.forClass(String.class);
        verify(conversations).countByMerchantIdAndModeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                merchant.capture(), mode.capture(), any(), any());
        assertThat(merchant.getValue()).isEqualTo(MERCHANT);
        assertThat(mode.getValue()).isEqualTo("test");
    }

    @Test
    @DisplayName("the counts land in the right buckets")
    void countsMapToBuckets() {
        stubZeros();
        when(actions.countByMerchantIdAndModeAndStateAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                eq(MERCHANT), eq("test"), eq(ActionState.EXECUTED), any(), any())).thenReturn(7L);
        when(actions.countByMerchantIdAndModeAndPolicyDecisionAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                eq(MERCHANT), eq("test"), eq(PolicyDecision.REFUSE), any(), any())).thenReturn(3L);
        when(approvals.countByMerchantIdAndModeAndStateAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                eq(MERCHANT), eq("test"), eq(ApprovalState.PENDING), any(), any())).thenReturn(2L);
        when(actions.countByMerchantIdAndModeAndPaymentIdIsNotNullAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                eq(MERCHANT), eq("test"), any(), any())).thenReturn(5L);

        SummaryService.SummaryView view = service.summarize(MERCHANT, "test",
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"));

        assertThat(view.actions().executed()).isEqualTo(7);
        assertThat(view.policyDecisions().refuse()).isEqualTo(3);
        assertThat(view.approvals().pending()).isEqualTo(2);
        assertThat(view.payments().agentInitiated()).isEqualTo(5);
    }

    private void stubZeros() {
        when(conversations.countByMerchantIdAndModeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                any(), any(), any(), any())).thenReturn(0L);
        when(actions.countByMerchantIdAndModeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                any(), any(), any(), any())).thenReturn(0L);
        when(actions.countByMerchantIdAndModeAndStateAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                any(), any(), any(), any(), any())).thenReturn(0L);
        when(actions.countByMerchantIdAndModeAndPolicyDecisionAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                any(), any(), any(), any(), any())).thenReturn(0L);
        when(actions.countByMerchantIdAndModeAndPaymentIdIsNotNullAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                any(), any(), any(), any())).thenReturn(0L);
        when(approvals.countByMerchantIdAndModeAndStateAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                any(), any(), any(), any(), any())).thenReturn(0L);
    }
}
