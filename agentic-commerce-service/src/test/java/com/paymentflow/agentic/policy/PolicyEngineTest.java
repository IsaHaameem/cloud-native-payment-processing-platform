package com.paymentflow.agentic.policy;

import com.paymentflow.agentic.checkout.CheckoutStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.UUID;

import static com.paymentflow.agentic.policy.PolicyFixtures.CONVERSATION_ID;
import static com.paymentflow.agentic.policy.PolicyFixtures.CURRENCY;
import static com.paymentflow.agentic.policy.PolicyFixtures.MAX_CONVERSATION_REFUND;
import static com.paymentflow.agentic.policy.PolicyFixtures.MAX_CONVERSATION_SPEND;
import static com.paymentflow.agentic.policy.PolicyFixtures.MAX_PAYMENT;
import static com.paymentflow.agentic.policy.PolicyFixtures.MAX_REFUND;
import static com.paymentflow.agentic.policy.PolicyFixtures.MAX_TOOL_CALLS;
import static com.paymentflow.agentic.policy.PolicyFixtures.MERCHANT_ID;
import static com.paymentflow.agentic.policy.PolicyFixtures.POLICY_VERSION;
import static com.paymentflow.agentic.policy.PolicyFixtures.REFUND_APPROVAL_THRESHOLD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The policy engine's contract, asserted as behaviour rather than as coverage.
 *
 * <p>Every test here runs without Spring, without a database and without a clock, which is
 * itself part of what is being asserted: an engine that needed any of those could not be
 * claimed to decide from server-side facts alone.
 */
class PolicyEngineTest {

    private final PolicyEngine engine = PolicyFixtures.engine();

    @Nested
    @DisplayName("permit")
    class Permit {

        @Test
        @DisplayName("a read tool is permitted and carries no budget")
        void readIsPermitted() {
            PolicyVerdict verdict = engine.evaluate(PolicyFixtures.read());

            assertThat(verdict.decision()).isEqualTo(PolicyDecision.PERMIT);
            assertThat(verdict.rule()).isEqualTo(PolicyRule.DEFAULT_PERMIT);
            assertThat(verdict.budgetRemainingMinor()).isNull();
            assertThat(verdict.policyVersion()).isEqualTo(POLICY_VERSION);
        }

        @Test
        @DisplayName("a payment inside every bound is permitted, and records the budget it was inside")
        void paymentWithinBoundsIsPermitted() {
            PolicyVerdict verdict = engine.evaluate(PolicyFixtures.payment(250_000L));

            assertThat(verdict.decision()).isEqualTo(PolicyDecision.PERMIT);
            assertThat(verdict.permitsExecution()).isTrue();
            assertThat(verdict.budgetRemainingMinor()).isEqualTo(MAX_CONVERSATION_SPEND);
        }

        @Test
        @DisplayName("a payment for exactly the cap is permitted — the cap is inclusive")
        void paymentAtTheCapIsPermitted() {
            assertThat(engine.evaluate(PolicyFixtures.payment(MAX_PAYMENT)).decision())
                    .isEqualTo(PolicyDecision.PERMIT);
        }

        @Test
        @DisplayName("a refund at or below the approval threshold executes on policy alone")
        void smallRefundIsPermitted() {
            PolicyVerdict verdict = engine.evaluate(PolicyFixtures.refund(REFUND_APPROVAL_THRESHOLD));

            assertThat(verdict.decision()).isEqualTo(PolicyDecision.PERMIT);
            assertThat(verdict.requiresApproval()).isFalse();
        }

        @Test
        @DisplayName("a payment against a LOCKED checkout is permitted — locking is what freezes the amount")
        void lockedCheckoutIsPayable() {
            PolicyRequest request = PolicyFixtures.payment(
                    PolicyFixtures.conversation(), 250_000L, CheckoutStatus.LOCKED, CURRENCY);

            assertThat(engine.evaluate(request).decision()).isEqualTo(PolicyDecision.PERMIT);
        }
    }

    @Nested
    @DisplayName("refuse")
    class Refuse {

        @Test
        @DisplayName("a payment above the per-action cap is refused")
        void paymentAboveCapIsRefused() {
            PolicyVerdict verdict = engine.evaluate(PolicyFixtures.payment(MAX_PAYMENT + 1));

            assertThat(verdict.decision()).isEqualTo(PolicyDecision.REFUSE);
            assertThat(verdict.rule()).isEqualTo(PolicyRule.PAYMENT_AMOUNT_CAP);
            assertThat(verdict.reasonCode()).isEqualTo("payment_amount_exceeds_cap");
            assertThat(verdict.permitsExecution()).isFalse();
        }

        @Test
        @DisplayName("a payment that would exceed the conversation's cumulative spend is refused")
        void conversationSpendBudgetIsEnforced() {
            PolicyRequest.Conversation spent = new PolicyRequest.Conversation(
                    CONVERSATION_ID, true, MAX_CONVERSATION_SPEND - 1_000L, 0, 3);
            PolicyRequest request = PolicyFixtures.payment(spent, 1_001L, CheckoutStatus.OPEN, CURRENCY);

            PolicyVerdict verdict = engine.evaluate(request);

            assertThat(verdict.rule()).isEqualTo(PolicyRule.CONVERSATION_SPEND_BUDGET);
            assertThat(verdict.budgetRemainingMinor()).isEqualTo(1_000L);
        }

        @Test
        @DisplayName("the amount that exactly consumes the remaining budget is permitted")
        void budgetBoundaryIsInclusive() {
            PolicyRequest.Conversation spent = new PolicyRequest.Conversation(
                    CONVERSATION_ID, true, MAX_CONVERSATION_SPEND - 1_000L, 0, 3);

            assertThat(engine.evaluate(PolicyFixtures.payment(spent, 1_000L, CheckoutStatus.OPEN, CURRENCY))
                    .decision()).isEqualTo(PolicyDecision.PERMIT);
        }

        @Test
        @DisplayName("a money action with no server-derived amount is refused, never guessed at")
        void unresolvedAmountIsRefused() {
            PolicyRequest request = new PolicyRequest(PolicyFixtures.actor(), PolicyFixtures.conversation(),
                    "complete_checkout", PolicyOperation.CHECKOUT_PAY,
                    new PolicyRequest.Target(PolicyFixtures.CHECKOUT_ID, CheckoutStatus.OPEN, null, null, CURRENCY));

            assertThat(engine.evaluate(request).rule()).isEqualTo(PolicyRule.AMOUNT_RESOLVED);
        }

        @Test
        @DisplayName("a zero or negative amount is not a resolved amount")
        void nonPositiveAmountIsRefused() {
            assertThat(engine.evaluate(PolicyFixtures.payment(0L)).rule()).isEqualTo(PolicyRule.AMOUNT_RESOLVED);
            assertThat(engine.evaluate(PolicyFixtures.payment(-1L)).rule()).isEqualTo(PolicyRule.AMOUNT_RESOLVED);
        }

        @Test
        @DisplayName("a currency the policy does not name is refused")
        void foreignCurrencyIsRefused() {
            PolicyRequest request = PolicyFixtures.payment(
                    PolicyFixtures.conversation(), 250_000L, CheckoutStatus.OPEN, "USD");

            assertThat(engine.evaluate(request).rule()).isEqualTo(PolicyRule.CURRENCY_PERMITTED);
        }

        @ParameterizedTest
        @EnumSource(value = CheckoutStatus.class, names = {"PAID", "CANCELLED", "EXPIRED"})
        @DisplayName("a checkout that is not payable is refused, whatever the amount")
        void unpayableCheckoutIsRefused(CheckoutStatus status) {
            PolicyRequest request = PolicyFixtures.payment(
                    PolicyFixtures.conversation(), 250_000L, status, CURRENCY);

            assertThat(engine.evaluate(request).rule()).isEqualTo(PolicyRule.CHECKOUT_PAYABLE);
        }

        @Test
        @DisplayName("a closed conversation can take no action at all, not even a read")
        void closedConversationIsRefused() {
            PolicyRequest.Conversation closed = new PolicyRequest.Conversation(CONVERSATION_ID, false, 0, 0, 0);
            PolicyRequest request = new PolicyRequest(PolicyFixtures.actor(), closed, "search_products",
                    PolicyOperation.CATALOG_READ, PolicyRequest.Target.none());

            assertThat(engine.evaluate(request).rule()).isEqualTo(PolicyRule.CONVERSATION_ACTIVE);
        }

        @Test
        @DisplayName("the ceiling permits exactly its own number of calls, and refuses the next")
        void ceilingPermitsExactlyItsOwnNumber() {
            // The counter includes the call being evaluated, so a count equal to the ceiling is
            // the last permitted call — not the first refused one. An earlier draft compared with
            // >= here and quietly made a ceiling of 60 mean 59.
            PolicyRequest.Conversation atCeiling = new PolicyRequest.Conversation(
                    CONVERSATION_ID, true, 0, 0, MAX_TOOL_CALLS);
            PolicyRequest lastPermitted = new PolicyRequest(PolicyFixtures.actor(), atCeiling,
                    "search_products", PolicyOperation.CATALOG_READ, PolicyRequest.Target.none());

            assertThat(engine.evaluate(lastPermitted).decision()).isEqualTo(PolicyDecision.PERMIT);
        }

        @Test
        @DisplayName("the tool-call ceiling applies to read tools too — a runaway agent exhausts this first")
        void toolCallCeilingAppliesToReads() {
            PolicyRequest.Conversation exhausted = new PolicyRequest.Conversation(
                    CONVERSATION_ID, true, 0, 0, MAX_TOOL_CALLS + 1);
            PolicyRequest request = new PolicyRequest(PolicyFixtures.actor(), exhausted, "search_products",
                    PolicyOperation.CATALOG_READ, PolicyRequest.Target.none());

            assertThat(engine.evaluate(request).rule()).isEqualTo(PolicyRule.TOOL_CALL_CEILING);
        }

        @Test
        @DisplayName("a mode other than test is refused — this extension is test-mode only")
        void liveModeIsRefused() {
            PolicyRequest.Actor live = new PolicyRequest.Actor(MERCHANT_ID, "live", "session-1", "principal");
            PolicyRequest request = new PolicyRequest(live, PolicyFixtures.conversation(), "complete_checkout",
                    PolicyOperation.CHECKOUT_PAY,
                    new PolicyRequest.Target(PolicyFixtures.CHECKOUT_ID, CheckoutStatus.OPEN, null, 1L, CURRENCY));

            assertThat(engine.evaluate(request).rule()).isEqualTo(PolicyRule.MODE_CONFINED);
        }

        @Test
        @DisplayName("an action with no identifiable actor cannot be attributed, so it is refused")
        void anonymousActorIsRefused() {
            PolicyRequest.Actor anonymous = new PolicyRequest.Actor(MERCHANT_ID, "test", "session-1", "  ");
            PolicyRequest request = new PolicyRequest(anonymous, PolicyFixtures.conversation(), "search_products",
                    PolicyOperation.CATALOG_READ, PolicyRequest.Target.none());

            assertThat(engine.evaluate(request).rule()).isEqualTo(PolicyRule.ACTOR_REQUIRED);
        }

        @Test
        @DisplayName("an unrecognised tool is refused by the engine as well as by the registry")
        void unregisteredToolIsRefused() {
            PolicyRequest request = new PolicyRequest(PolicyFixtures.actor(), PolicyFixtures.conversation(),
                    "http_request", null, PolicyRequest.Target.none());

            PolicyVerdict verdict = engine.evaluate(request);

            assertThat(verdict.rule()).isEqualTo(PolicyRule.TOOL_ALLOW_LIST);
            assertThat(verdict.reason()).contains("http_request");
        }

        @Test
        @DisplayName("a payment naming no checkout is refused before any amount is considered")
        void paymentWithoutCheckoutIsRefused() {
            PolicyRequest request = new PolicyRequest(PolicyFixtures.actor(), PolicyFixtures.conversation(),
                    "complete_checkout", PolicyOperation.CHECKOUT_PAY,
                    new PolicyRequest.Target(null, CheckoutStatus.OPEN, null, 250_000L, CURRENCY));

            assertThat(engine.evaluate(request).rule()).isEqualTo(PolicyRule.PAYMENT_TARGET_REQUIRED);
        }

        @Test
        @DisplayName("a refund naming no payment is refused")
        void refundWithoutPaymentIsRefused() {
            PolicyRequest request = new PolicyRequest(PolicyFixtures.actor(), PolicyFixtures.conversation(),
                    "request_refund", PolicyOperation.REFUND_CREATE,
                    new PolicyRequest.Target(null, null, null, 50_000L, CURRENCY));

            assertThat(engine.evaluate(request).rule()).isEqualTo(PolicyRule.REFUND_TARGET_REQUIRED);
        }
    }

    @Nested
    @DisplayName("approval")
    class Approval {

        @Test
        @DisplayName("a refund above the threshold requires approval and does not permit execution")
        void largeRefundRequiresApproval() {
            PolicyVerdict verdict = engine.evaluate(PolicyFixtures.refund(REFUND_APPROVAL_THRESHOLD + 1));

            assertThat(verdict.decision()).isEqualTo(PolicyDecision.REQUIRES_APPROVAL);
            assertThat(verdict.rule()).isEqualTo(PolicyRule.REFUND_APPROVAL_THRESHOLD);
            assertThat(verdict.requiresApproval()).isTrue();
            assertThat(verdict.permitsExecution()).isFalse();
        }

        @Test
        @DisplayName("a refund above the hard cap is REFUSED, not sent for approval")
        void hardCapBeatsApprovalThreshold() {
            PolicyVerdict verdict = engine.evaluate(PolicyFixtures.refund(MAX_REFUND + 1));

            assertThat(verdict.decision()).isEqualTo(PolicyDecision.REFUSE);
            assertThat(verdict.rule()).isEqualTo(PolicyRule.REFUND_AMOUNT_CAP);
            assertThat(verdict.reason()).contains("No approval can permit this");
        }

        @Test
        @DisplayName("a refund beyond the conversation refund budget is REFUSED, not sent for approval")
        void refundBudgetBeatsApprovalThreshold() {
            PolicyRequest.Conversation refunded = new PolicyRequest.Conversation(
                    CONVERSATION_ID, true, 0, MAX_CONVERSATION_REFUND, 1);

            PolicyVerdict verdict = engine.evaluate(
                    PolicyFixtures.refund(refunded, REFUND_APPROVAL_THRESHOLD + 1, CURRENCY));

            assertThat(verdict.decision()).isEqualTo(PolicyDecision.REFUSE);
            assertThat(verdict.rule()).isEqualTo(PolicyRule.CONVERSATION_REFUND_BUDGET);
            assertThat(verdict.budgetRemainingMinor()).isZero();
        }

        @Test
        @DisplayName("no payment rule ever asks for approval — payment is permit or refuse")
        void paymentNeverRequiresApproval() {
            for (long amount : new long[] {1L, 250_000L, MAX_PAYMENT, MAX_PAYMENT + 1, Long.MAX_VALUE}) {
                assertThat(engine.evaluate(PolicyFixtures.payment(amount)).requiresApproval())
                        .as("payment of %d", amount)
                        .isFalse();
            }
        }
    }

    @Nested
    @DisplayName("determinism")
    class Determinism {

        @Test
        @DisplayName("equal requests produce equal verdicts, every time")
        void sameRequestSameVerdict() {
            PolicyRequest first = PolicyFixtures.payment(250_000L);
            PolicyRequest second = PolicyFixtures.payment(250_000L);

            assertThat(first).isEqualTo(second);
            assertThat(engine.evaluate(first)).isEqualTo(engine.evaluate(second));
            assertThat(engine.evaluate(first)).isEqualTo(engine.evaluate(first));
        }

        @Test
        @DisplayName("every verdict carries the policy version it was decided under")
        void everyVerdictCarriesTheVersion() {
            assertThat(engine.evaluate(PolicyFixtures.read()).policyVersion()).isEqualTo(POLICY_VERSION);
            assertThat(engine.evaluate(PolicyFixtures.payment(MAX_PAYMENT + 1)).policyVersion())
                    .isEqualTo(POLICY_VERSION);
            assertThat(engine.evaluate(PolicyFixtures.refund(MAX_REFUND)).policyVersion())
                    .isEqualTo(POLICY_VERSION);
        }

        @Test
        @DisplayName("a rule's decision is fixed by the rule, so rule id and outcome can never disagree")
        void ruleAndDecisionAgree() {
            for (PolicyRule rule : PolicyRule.values()) {
                assertThat(rule.id()).isNotBlank().hasSizeLessThanOrEqualTo(64);
                assertThat(rule.reasonCode()).isNotBlank().hasSizeLessThanOrEqualTo(64);
            }
            assertThat(PolicyRule.REFUND_APPROVAL_THRESHOLD.decision())
                    .isEqualTo(PolicyDecision.REQUIRES_APPROVAL);
            assertThat(PolicyRule.DEFAULT_PERMIT.decision()).isEqualTo(PolicyDecision.PERMIT);
        }

        @Test
        @DisplayName("a verdict's reason fits the column it is stored in")
        void reasonsFitTheSchema() {
            assertThat(engine.evaluate(PolicyFixtures.payment(Long.MAX_VALUE)).reason())
                    .hasSizeLessThanOrEqualTo(500);
        }
    }

    @Nested
    @DisplayName("fail-closed configuration")
    class FailClosed {

        @Test
        @DisplayName("a blanked payment cap disables payments rather than unbounding them")
        void zeroPaymentCapRefusesEverything() {
            PolicyEngine zeroed = new PolicyEngine(PolicyFixtures.properties(
                    new AgenticPropertiesPolicyBuilder().maxPaymentAmountMinor(0).build()));

            assertThat(zeroed.evaluate(PolicyFixtures.payment(1L)).rule())
                    .isEqualTo(PolicyRule.PAYMENT_AMOUNT_CAP);
        }

        @Test
        @DisplayName("a blanked refund cap disables refunds rather than unbounding them")
        void zeroRefundCapRefusesEverything() {
            PolicyEngine zeroed = new PolicyEngine(PolicyFixtures.properties(
                    new AgenticPropertiesPolicyBuilder().maxRefundAmountMinor(0).build()));

            assertThat(zeroed.evaluate(PolicyFixtures.refund(1L)).rule())
                    .isEqualTo(PolicyRule.REFUND_AMOUNT_CAP);
        }

        @Test
        @DisplayName("a blanked tool-call ceiling stops the agent rather than letting it run forever")
        void zeroToolCeilingStopsEverything() {
            PolicyEngine zeroed = new PolicyEngine(PolicyFixtures.properties(
                    new AgenticPropertiesPolicyBuilder().maxToolCallsPerConversation(0).build()));

            assertThat(zeroed.evaluate(PolicyFixtures.read()).rule()).isEqualTo(PolicyRule.TOOL_CALL_CEILING);
        }
    }

    @Nested
    @DisplayName("request integrity")
    class RequestIntegrity {

        @Test
        @DisplayName("a request cannot be built without the facts a decision has to be reproducible from")
        void missingFactsAreRejected() {
            assertThatThrownBy(() -> new PolicyRequest(null, PolicyFixtures.conversation(), "t",
                    PolicyOperation.CATALOG_READ, PolicyRequest.Target.none()))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> new PolicyRequest.Actor(null, "test", "s", "p"))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> new PolicyRequest.Conversation(UUID.randomUUID(), true, -1, 0, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the tool's risk class is read off its operation, so the two cannot disagree")
        void categoryIsDerivedFromOperation() {
            assertThat(PolicyFixtures.payment(1L).category()).isEqualTo(ToolCategory.PAYMENT);
            assertThat(PolicyFixtures.refund(1L).category()).isEqualTo(ToolCategory.REFUND);
            assertThat(PolicyFixtures.read().category()).isEqualTo(ToolCategory.READ);
            assertThat(PolicyOperation.CHECKOUT_PAY.movesMoney()).isTrue();
            assertThat(PolicyOperation.CHECKOUT_CREATE.movesMoney()).isFalse();
        }
    }
}
