package com.paymentflow.agentic.tool.money;

import com.paymentflow.agentic.action.AgentActionJournal;
import com.paymentflow.agentic.checkout.Checkout;
import com.paymentflow.agentic.checkout.CheckoutService;
import com.paymentflow.agentic.checkout.CheckoutStatus;
import com.paymentflow.agentic.error.AgenticException;
import com.paymentflow.agentic.idempotency.IdempotencyKeys;
import com.paymentflow.agentic.platform.PaymentFlowClient;
import com.paymentflow.agentic.platform.PaymentView;
import com.paymentflow.agentic.platform.PlatformResponse;
import com.paymentflow.agentic.policy.PolicyOperation;
import com.paymentflow.agentic.policy.ToolCategory;
import com.paymentflow.agentic.tool.ResolvedAction;
import com.paymentflow.agentic.tool.ToolArguments;
import com.paymentflow.agentic.tool.ToolContext;
import com.paymentflow.agentic.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code complete_checkout}'s contract, and with it the central claim of the whole service:
 * the amount that reaches the platform is the checkout's, not the model's.
 *
 * <p>The mocked {@link PaymentFlowClient} is what makes the amount assertion meaningful. It
 * captures exactly what would have gone on the wire, so a test that passes here would have sent
 * that number to a real gateway.
 */
class CompleteCheckoutToolTest {

    private static final UUID MERCHANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONVERSATION = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CHECKOUT = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID PAYMENT = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID ACTION = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final String TOKEN = "pm_card_visa";
    private static final long CHECKOUT_TOTAL = 250_000L;

    private CheckoutService checkoutService;
    private PaymentFlowClient platform;
    private InstrumentAllowList instruments;
    private AgentActionJournal journal;
    private CompleteCheckoutTool tool;

    @BeforeEach
    void setUp() {
        checkoutService = mock(CheckoutService.class);
        platform = mock(PaymentFlowClient.class);
        instruments = mock(InstrumentAllowList.class);
        journal = mock(AgentActionJournal.class);
        tool = new CompleteCheckoutTool(checkoutService, platform, instruments, journal);

        when(instruments.isPermitted(TOKEN)).thenReturn(true);
        when(journal.beginStep(any(), anyString(), anyString(), any())).thenReturn(UUID.randomUUID());
    }

    // ── Validation ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("there is no amount argument, so a model that sends one is rejected")
    void amountIsNotAnArgument() {
        assertThatThrownBy(() -> tool.validate(arguments(Map.of(
                "checkoutId", CHECKOUT.toString(),
                "instrumentToken", TOKEN,
                "amountMinor", 1))))
                .isInstanceOf(AgenticException.class)
                .hasMessageContaining("amountMinor");
    }

    @Test
    @DisplayName("an instrument the merchant does not offer is rejected before anything is locked")
    void modelInventedInstrumentIsRejected() {
        when(instruments.isPermitted("tok_i_made_this_up")).thenReturn(false);

        assertThatThrownBy(() -> tool.validate(arguments(Map.of(
                "checkoutId", CHECKOUT.toString(),
                "instrumentToken", "tok_i_made_this_up"))))
                .isInstanceOf(AgenticException.class)
                .hasMessageContaining("not one the merchant offers");

        verify(checkoutService, never()).lockForPayment(any(), any(), any());
    }

    @Test
    @DisplayName("the tool is classified as a payment, so the money rules apply to it")
    void isClassifiedAsPayment() {
        assertThat(tool.spec().operation()).isEqualTo(PolicyOperation.CHECKOUT_PAY);
        assertThat(tool.spec().category()).isEqualTo(ToolCategory.PAYMENT);
        assertThat(tool.spec().movesMoney()).isTrue();
    }

    // ── Resolution ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the amount is read off the locked checkout, which is what freezes it")
    void amountComesFromTheCheckout() {
        // Built before the outer stubbing begins: a mock assembled inside a thenReturn(...)
        // argument leaves Mockito mid-stub and fails with UnfinishedStubbingException.
        Checkout locked = checkout(CheckoutStatus.LOCKED, CHECKOUT_TOTAL);
        when(checkoutService.lockForPayment(MERCHANT, "test", CHECKOUT)).thenReturn(locked);

        ResolvedAction resolved = tool.resolve(context(), validInput());

        assertThat(resolved.target().amountMinor()).isEqualTo(CHECKOUT_TOTAL);
        assertThat(resolved.target().currency()).isEqualTo("INR");
        assertThat(resolved.target().checkoutId()).isEqualTo(CHECKOUT);
        assertThat(resolved.target().checkoutStatus()).isEqualTo(CheckoutStatus.LOCKED);
    }

    @Test
    @DisplayName("the redacted summary records the derived amount, not a model-supplied one")
    void summaryRecordsTheDerivedAmount() {
        Checkout locked = checkout(CheckoutStatus.LOCKED, CHECKOUT_TOTAL);
        when(checkoutService.lockForPayment(any(), any(), any())).thenReturn(locked);

        ResolvedAction resolved = tool.resolve(context(), validInput());

        assertThat(resolved.inputSummary()).contains("amountMinor=" + CHECKOUT_TOTAL);
    }

    // ── Execution ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the checkout's own total is what reaches the platform")
    void checkoutTotalIsWhatIsCharged() {
        stubHappyPath();

        tool.execute(context(), validInput(), resolvedFor(CHECKOUT_TOTAL));

        ArgumentCaptor<Long> amount = ArgumentCaptor.forClass(Long.class);
        verify(platform).createPayment(any(), anyString(), amount.capture(), eq("INR"), any(), eq(TOKEN),
                any());
        assertThat(amount.getValue()).isEqualTo(CHECKOUT_TOTAL);
    }

    @Test
    @DisplayName("create, authorize and capture each carry their own derived idempotency key")
    void eachStepDerivesItsOwnKey() {
        stubHappyPath();

        tool.execute(context(), validInput(), resolvedFor(CHECKOUT_TOTAL));

        String createKey = IdempotencyKeys.forStep(CONVERSATION, "complete_checkout", CHECKOUT,
                CompleteCheckoutTool.STEP_CREATE);
        String authorizeKey = IdempotencyKeys.forStep(CONVERSATION, "complete_checkout", CHECKOUT,
                CompleteCheckoutTool.STEP_AUTHORIZE);
        String captureKey = IdempotencyKeys.forStep(CONVERSATION, "complete_checkout", CHECKOUT,
                CompleteCheckoutTool.STEP_CAPTURE);

        assertThat(createKey).isNotEqualTo(authorizeKey).isNotEqualTo(captureKey);
        verify(platform).createPayment(any(), eq(createKey), anyLong(), any(), any(), any(), any());
        verify(platform).authorizePayment(any(), eq(authorizeKey), eq(PAYMENT));
        verify(platform).capturePayment(any(), eq(captureKey), eq(PAYMENT));
    }

    @Test
    @DisplayName("a step is opened before its call and completed after — a crash leaves a trace")
    void everyPlatformCallIsJournalled() {
        stubHappyPath();

        tool.execute(context(), validInput(), resolvedFor(CHECKOUT_TOTAL));

        verify(journal).beginStep(eq(ACTION), eq(CompleteCheckoutTool.STEP_CREATE), anyString(), any());
        verify(journal).beginStep(eq(ACTION), eq(CompleteCheckoutTool.STEP_AUTHORIZE), anyString(), any());
        verify(journal).beginStep(eq(ACTION), eq(CompleteCheckoutTool.STEP_CAPTURE), anyString(), any());
    }

    @Test
    @DisplayName("a successful payment marks the checkout paid")
    void successMarksTheCheckoutPaid() {
        stubHappyPath();

        ToolResult result = tool.execute(context(), validInput(), resolvedFor(CHECKOUT_TOTAL));

        assertThat(result.ok()).isTrue();
        verify(checkoutService).markPaid(MERCHANT, "test", CHECKOUT, PAYMENT, null);
        verify(checkoutService, never()).releaseLock(any());
    }

    @Test
    @DisplayName("a decline releases the basket and reports the acquirer's own reason")
    void declineReleasesTheBasketAndReportsTheReason() {
        when(platform.createPayment(any(), anyString(), anyLong(), any(), any(), any(), any()))
                .thenReturn(response(payment("created", null)));
        when(platform.authorizePayment(any(), anyString(), any()))
                .thenReturn(response(payment("failed", "insufficient_funds")));

        ToolResult result = tool.execute(context(), validInput(), resolvedFor(CHECKOUT_TOTAL));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorMessage()).contains("insufficient_funds");
        assertThat(result.payload()).isInstanceOf(CompleteCheckoutTool.Result.class);
        verify(checkoutService).releaseLock(CHECKOUT);
        verify(checkoutService, never()).markPaid(any(), any(), any(), any(), any());
        verify(platform, never()).capturePayment(any(), anyString(), any());
    }

    @Test
    @DisplayName("a money tool invoked outside an action refuses rather than charging unrecorded")
    void refusesWithoutAnActionToRecordAgainst() {
        ToolContext orphan = new ToolContext(MERCHANT, "test", CONVERSATION, "session-1", "principal",
                UUID.randomUUID().toString(), null);

        assertThatThrownBy(() -> tool.execute(orphan, validInput(), resolvedFor(CHECKOUT_TOTAL)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside an agent action");

        verify(platform, never()).createPayment(any(), anyString(), anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("the payment carries metadata joining it back to the conversation that caused it")
    void paymentCarriesTheAuditJoin() {
        stubHappyPath();

        tool.execute(context(), validInput(), resolvedFor(CHECKOUT_TOTAL));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(platform).createPayment(any(), anyString(), anyLong(), any(), any(), any(),
                metadata.capture());
        assertThat(metadata.getValue())
                .containsEntry("agent_conversation_id", CONVERSATION.toString())
                .containsEntry("agent_checkout_id", CHECKOUT.toString())
                .containsEntry("agent_action_id", ACTION.toString());
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────────────

    private void stubHappyPath() {
        when(platform.createPayment(any(), anyString(), anyLong(), any(), any(), any(), any()))
                .thenReturn(response(payment("created", null)));
        when(platform.authorizePayment(any(), anyString(), any()))
                .thenReturn(response(payment("authorized", null)));
        when(platform.capturePayment(any(), anyString(), any()))
                .thenReturn(response(payment("captured", null)));
    }

    private static ToolArguments arguments(Map<String, Object> raw) {
        return ToolArguments.of("complete_checkout", raw);
    }

    private CompleteCheckoutTool.Input validInput() {
        return tool.validate(arguments(Map.of("checkoutId", CHECKOUT.toString(),
                "instrumentToken", TOKEN)));
    }

    private static ToolContext context() {
        return new ToolContext(MERCHANT, "test", CONVERSATION, "session-1", "principal",
                UUID.randomUUID().toString(), ACTION);
    }

    private static ResolvedAction resolvedFor(long amountMinor) {
        return new ResolvedAction(
                com.paymentflow.agentic.policy.PolicyRequest.Target.ofCheckout(
                        CHECKOUT, CheckoutStatus.LOCKED, amountMinor, "INR"),
                Map.of("checkoutId", CHECKOUT.toString(), "amountMinor", amountMinor),
                "Charge %d INR for checkout %s.".formatted(amountMinor, CHECKOUT));
    }

    private static Checkout checkout(CheckoutStatus status, long totalMinor) {
        Checkout checkout = mock(Checkout.class);
        when(checkout.getId()).thenReturn(CHECKOUT);
        when(checkout.getStatus()).thenReturn(status);
        when(checkout.getTotalMinor()).thenReturn(totalMinor);
        when(checkout.getCurrency()).thenReturn("INR");
        return checkout;
    }

    private static PaymentView payment(String status, String failureReason) {
        return new PaymentView(PAYMENT.toString(), status, CHECKOUT_TOTAL,
                "captured".equals(status) ? CHECKOUT_TOTAL : 0, 0, "INR", "Order", failureReason,
                TOKEN, "test", Instant.parse("2026-08-22T10:00:00Z"),
                Instant.parse("2026-08-22T10:00:01Z"));
    }

    private static PlatformResponse<PaymentView> response(PaymentView payment) {
        return new PlatformResponse<>(payment, 200, "req_test", "corr_test");
    }
}
