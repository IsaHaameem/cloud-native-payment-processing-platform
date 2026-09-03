package com.paymentflow.agentic.approval;

import com.paymentflow.agentic.error.AgenticErrorCode;
import com.paymentflow.agentic.error.AgenticException;
import com.paymentflow.agentic.policy.PolicyOperation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.paymentflow.agentic.approval.ApprovalFixtures.ACTION_ID;
import static com.paymentflow.agentic.approval.ApprovalFixtures.CHECKOUT_ID;
import static com.paymentflow.agentic.approval.ApprovalFixtures.CONVERSATION_ID;
import static com.paymentflow.agentic.approval.ApprovalFixtures.CURRENCY;
import static com.paymentflow.agentic.approval.ApprovalFixtures.MERCHANT_ID;
import static com.paymentflow.agentic.approval.ApprovalFixtures.MODE;
import static com.paymentflow.agentic.approval.ApprovalFixtures.OTHER_MERCHANT_ID;
import static com.paymentflow.agentic.approval.ApprovalFixtures.OTHER_PAYMENT_ID;
import static com.paymentflow.agentic.approval.ApprovalFixtures.PAYMENT_ID;
import static com.paymentflow.agentic.approval.ApprovalFixtures.REFUND_AMOUNT;
import static com.paymentflow.agentic.approval.ApprovalFixtures.T0;
import static com.paymentflow.agentic.approval.ApprovalFixtures.pendingRefund;
import static com.paymentflow.agentic.approval.ApprovalFixtures.refundBinding;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The approval aggregate's contract.
 *
 * <p>These are the tests the whole gate rests on. If any of them can be made to pass while
 * the underlying financial operation still runs, the approval workflow is decoration.
 */
class ApprovalTest {

    private static final Duration TTL = Duration.ofMinutes(30);

    @Nested
    @DisplayName("approval required")
    class Required {

        @Test
        @DisplayName("a new approval is PENDING and not redeemable — the operation cannot execute")
        void newApprovalBlocksExecution() {
            Approval approval = pendingRefund(TTL);

            assertThat(approval.getState()).isEqualTo(ApprovalState.PENDING);
            assertThat(approval.getState().isRedeemable()).isFalse();
            assertThatThrownBy(() -> approval.redeem(refundBinding(), T0))
                    .isInstanceOf(AgenticException.class)
                    .satisfies(e -> assertThat(((AgenticException) e).agenticErrorCode())
                            .isEqualTo(AgenticErrorCode.APPROVAL_NOT_PENDING));
        }

        @Test
        @DisplayName("the amount and currency are frozen onto the request, not looked up later")
        void bindingIsFrozenAtRequestTime() {
            Approval approval = pendingRefund(TTL);

            assertThat(approval.getAmountMinor()).isEqualTo(REFUND_AMOUNT);
            assertThat(approval.getCurrency()).isEqualTo(CURRENCY);
            assertThat(approval.getRequestedOperation()).isEqualTo(PolicyOperation.REFUND_CREATE);
            assertThat(approval.getPaymentId()).isEqualTo(PAYMENT_ID);
            assertThat(approval.binding()).isEqualTo(refundBinding());
        }
    }

    @Nested
    @DisplayName("approval granted")
    class Granted {

        @Test
        @DisplayName("a granted approval redeems exactly once, against the binding it was granted for")
        void grantedApprovalRedeemsOnce() {
            Approval approval = pendingRefund(TTL);
            approval.approve("ops@example.test", T0);

            assertThat(approval.getState()).isEqualTo(ApprovalState.APPROVED);
            assertThat(approval.getDecidedBy()).isEqualTo("ops@example.test");
            assertThat(approval.getDecidedAt()).isEqualTo(T0);

            approval.redeem(refundBinding(), T0.plusSeconds(5));

            assertThat(approval.getState()).isEqualTo(ApprovalState.CONSUMED);
        }

        @Test
        @DisplayName("a consumed approval cannot authorise a second execution")
        void consumedApprovalCannotBeSpentTwice() {
            Approval approval = pendingRefund(TTL);
            approval.approve("ops", T0);
            approval.redeem(refundBinding(), T0.plusSeconds(1));

            assertThatThrownBy(() -> approval.redeem(refundBinding(), T0.plusSeconds(2)))
                    .isInstanceOf(AgenticException.class)
                    .hasMessageContaining("CONSUMED");
        }
    }

    @Nested
    @DisplayName("approval rejected")
    class Rejected {

        @Test
        @DisplayName("a denied approval is terminal and can never execute")
        void deniedApprovalCannotExecute() {
            Approval approval = pendingRefund(TTL);
            approval.deny("ops", "The customer has not returned the item.", T0);

            assertThat(approval.getState()).isEqualTo(ApprovalState.DENIED);
            assertThat(approval.getReason()).isEqualTo("The customer has not returned the item.");
            assertThatThrownBy(() -> approval.redeem(refundBinding(), T0.plusSeconds(1)))
                    .isInstanceOf(AgenticException.class)
                    .hasMessageContaining("DENIED");
        }

        @Test
        @DisplayName("a decided approval cannot be decided again in the other direction")
        void deniedApprovalCannotBeApproved() {
            Approval approval = pendingRefund(TTL);
            approval.deny("ops", "no", T0);

            assertThatThrownBy(() -> approval.approve("someone-else", T0.plusSeconds(1)))
                    .isInstanceOf(AgenticException.class)
                    .satisfies(e -> assertThat(((AgenticException) e).agenticErrorCode())
                            .isEqualTo(AgenticErrorCode.APPROVAL_NOT_PENDING));
        }
    }

    @Nested
    @DisplayName("approval expired")
    class Expired {

        @Test
        @DisplayName("a request nobody answered in time expires and cannot then be approved")
        void staleRequestExpires() {
            Approval approval = pendingRefund(TTL);

            assertThatThrownBy(() -> approval.approve("ops", T0.plus(TTL).plusSeconds(1)))
                    .isInstanceOf(AgenticException.class);
            assertThat(approval.getState()).isEqualTo(ApprovalState.EXPIRED);
        }

        @Test
        @DisplayName("a GRANTED approval left unredeemed past its expiry is dead — this is the stale-approval case")
        void staleGrantCannotBeRedeemed() {
            Approval approval = pendingRefund(TTL);
            approval.approve("ops", T0.plusSeconds(10));

            assertThatThrownBy(() -> approval.redeem(refundBinding(), T0.plus(TTL).plusSeconds(1)))
                    .isInstanceOf(AgenticException.class)
                    .satisfies(e -> assertThat(((AgenticException) e).agenticErrorCode())
                            .isEqualTo(AgenticErrorCode.APPROVAL_EXPIRED));
            assertThat(approval.getState()).isEqualTo(ApprovalState.EXPIRED);
        }

        @Test
        @DisplayName("redeeming at exactly the expiry instant still works — expiry is strictly after")
        void expiryBoundaryIsInclusive() {
            Approval approval = pendingRefund(TTL);
            approval.approve("ops", T0);

            approval.redeem(refundBinding(), T0.plus(TTL));

            assertThat(approval.getState()).isEqualTo(ApprovalState.CONSUMED);
        }

        @Test
        @DisplayName("an expired approval stays expired; a later look does not revive it")
        void expiryIsTerminal() {
            Approval approval = pendingRefund(TTL);
            approval.expireIfDue(T0.plus(TTL).plusSeconds(1));

            assertThat(approval.getState()).isEqualTo(ApprovalState.EXPIRED);

            approval.expireIfDue(T0);

            assertThat(approval.getState()).isEqualTo(ApprovalState.EXPIRED);
        }
    }

    @Nested
    @DisplayName("modified action after approval")
    class Modified {

        @Test
        @DisplayName("a larger amount than was approved is refused, and the refusal names the amount")
        void amountCannotGrowAfterApproval() {
            Approval approval = pendingRefund(TTL);
            approval.approve("ops", T0);

            assertThatThrownBy(() -> approval.redeem(refundBinding(REFUND_AMOUNT * 10), T0.plusSeconds(1)))
                    .isInstanceOf(AgenticException.class)
                    .satisfies(e -> assertThat(((AgenticException) e).agenticErrorCode())
                            .isEqualTo(AgenticErrorCode.APPROVAL_AMOUNT_CHANGED))
                    .hasMessageContaining("amount");
        }

        @Test
        @DisplayName("a smaller amount is refused too — an approval authorises a number, not a ceiling")
        void amountCannotShrinkEither() {
            Approval approval = pendingRefund(TTL);
            approval.approve("ops", T0);

            assertThatThrownBy(() -> approval.redeem(refundBinding(1L), T0.plusSeconds(1)))
                    .isInstanceOf(AgenticException.class)
                    .hasMessageContaining("amount");
        }

        @Test
        @DisplayName("a different payment cannot be refunded under this approval")
        void targetCannotBeRepointed() {
            Approval approval = pendingRefund(TTL);
            approval.approve("ops", T0);
            ApprovalBinding elsewhere = new ApprovalBinding(MERCHANT_ID, MODE, PolicyOperation.REFUND_CREATE,
                    null, OTHER_PAYMENT_ID, REFUND_AMOUNT, CURRENCY);

            assertThatThrownBy(() -> approval.redeem(elsewhere, T0.plusSeconds(1)))
                    .isInstanceOf(AgenticException.class)
                    .hasMessageContaining("payment");
        }

        @Test
        @DisplayName("a different currency cannot be substituted")
        void currencyCannotChange() {
            Approval approval = pendingRefund(TTL);
            approval.approve("ops", T0);
            ApprovalBinding otherCurrency = new ApprovalBinding(MERCHANT_ID, MODE, PolicyOperation.REFUND_CREATE,
                    null, PAYMENT_ID, REFUND_AMOUNT, "USD");

            assertThatThrownBy(() -> approval.redeem(otherCurrency, T0.plusSeconds(1)))
                    .isInstanceOf(AgenticException.class)
                    .hasMessageContaining("currency");
        }

        @Test
        @DisplayName("a refund approval cannot be spent on a payment")
        void operationCannotChange() {
            Approval approval = pendingRefund(TTL);
            approval.approve("ops", T0);
            ApprovalBinding payment = new ApprovalBinding(MERCHANT_ID, MODE, PolicyOperation.CHECKOUT_PAY,
                    CHECKOUT_ID, null, REFUND_AMOUNT, CURRENCY);

            assertThatThrownBy(() -> approval.redeem(payment, T0.plusSeconds(1)))
                    .isInstanceOf(AgenticException.class)
                    .hasMessageContaining("operation");
        }

        @Test
        @DisplayName("another merchant cannot spend this approval")
        void merchantCannotChange() {
            Approval approval = pendingRefund(TTL);
            approval.approve("ops", T0);
            ApprovalBinding elsewhere = new ApprovalBinding(OTHER_MERCHANT_ID, MODE,
                    PolicyOperation.REFUND_CREATE, null, PAYMENT_ID, REFUND_AMOUNT, CURRENCY);

            assertThatThrownBy(() -> approval.redeem(elsewhere, T0.plusSeconds(1)))
                    .isInstanceOf(AgenticException.class)
                    .hasMessageContaining("merchant");
        }

        @Test
        @DisplayName("a rejected redemption leaves the approval spendable for the correct action")
        void failedRedemptionDoesNotConsume() {
            Approval approval = pendingRefund(TTL);
            approval.approve("ops", T0);

            assertThatThrownBy(() -> approval.redeem(refundBinding(1L), T0.plusSeconds(1)))
                    .isInstanceOf(AgenticException.class);
            assertThat(approval.getState()).isEqualTo(ApprovalState.APPROVED);

            approval.redeem(refundBinding(), T0.plusSeconds(2));

            assertThat(approval.getState()).isEqualTo(ApprovalState.CONSUMED);
        }
    }

    @Nested
    @DisplayName("duplicate approval")
    class Duplicate {

        @Test
        @DisplayName("an approved approval cannot be approved a second time")
        void doubleApproveIsRefused() {
            Approval approval = pendingRefund(TTL);
            approval.approve("ops", T0);

            assertThatThrownBy(() -> approval.approve("ops", T0.plusSeconds(1)))
                    .isInstanceOf(AgenticException.class)
                    .satisfies(e -> assertThat(((AgenticException) e).agenticErrorCode())
                            .isEqualTo(AgenticErrorCode.APPROVAL_NOT_PENDING));
            assertThat(approval.getDecidedAt()).isEqualTo(T0);
        }

        @Test
        @DisplayName("an approval is tied to one action, and that tie is not writable")
        void oneApprovalPerAction() {
            Approval approval = Approval.request(ACTION_ID, CONVERSATION_ID, "request_refund", refundBinding(),
                    "reason", T0.plus(TTL));

            assertThat(approval.getAgentActionId()).isEqualTo(ACTION_ID);
            assertThat(Approval.class.getDeclaredMethods())
                    .as("no setter may exist for what an approval covers")
                    .noneMatch(method -> method.getName().startsWith("set"));
        }
    }
}
