package com.paymentflow.agentic.approval;

import com.paymentflow.agentic.policy.PolicyOperation;
import com.paymentflow.agentic.policy.PolicyRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.paymentflow.agentic.approval.ApprovalFixtures.CHECKOUT_ID;
import static com.paymentflow.agentic.approval.ApprovalFixtures.CONVERSATION_ID;
import static com.paymentflow.agentic.approval.ApprovalFixtures.CURRENCY;
import static com.paymentflow.agentic.approval.ApprovalFixtures.MERCHANT_ID;
import static com.paymentflow.agentic.approval.ApprovalFixtures.MODE;
import static com.paymentflow.agentic.approval.ApprovalFixtures.PAYMENT_ID;
import static com.paymentflow.agentic.approval.ApprovalFixtures.REFUND_AMOUNT;
import static com.paymentflow.agentic.approval.ApprovalFixtures.refundBinding;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The binding comparison, field by field.
 *
 * <p>{@link ApprovalTest} asserts that a changed action is refused. This class asserts the
 * narrower thing underneath it: that every field which could change is actually compared, and
 * that the one field with a deliberate tolerance has exactly that tolerance and no more.
 */
class ApprovalBindingTest {

    @Test
    @DisplayName("an identical binding has no difference")
    void identicalBindingsMatch() {
        assertThat(refundBinding().firstDifferenceFrom(refundBinding())).isNull();
        assertThat(refundBinding().matches(refundBinding())).isTrue();
    }

    @Test
    @DisplayName("every field that could change is compared")
    void everyFieldIsCompared() {
        ApprovalBinding base = refundBinding();

        assertThat(base.firstDifferenceFrom(with(base, b -> new ApprovalBinding(
                ApprovalFixtures.OTHER_MERCHANT_ID, b.mode(), b.operation(), b.checkoutId(), b.paymentId(),
                b.amountMinor(), b.currency())))).isEqualTo("merchant");

        assertThat(base.firstDifferenceFrom(new ApprovalBinding(MERCHANT_ID, "live",
                PolicyOperation.REFUND_CREATE, null, PAYMENT_ID, REFUND_AMOUNT, CURRENCY))).isEqualTo("mode");

        assertThat(base.firstDifferenceFrom(new ApprovalBinding(MERCHANT_ID, MODE,
                PolicyOperation.CHECKOUT_PAY, null, PAYMENT_ID, REFUND_AMOUNT, CURRENCY)))
                .isEqualTo("operation");

        assertThat(base.firstDifferenceFrom(refundBinding(REFUND_AMOUNT + 1))).isEqualTo("amount");

        assertThat(base.firstDifferenceFrom(new ApprovalBinding(MERCHANT_ID, MODE,
                PolicyOperation.REFUND_CREATE, null, PAYMENT_ID, REFUND_AMOUNT, "USD"))).isEqualTo("currency");

        assertThat(base.firstDifferenceFrom(new ApprovalBinding(MERCHANT_ID, MODE,
                PolicyOperation.REFUND_CREATE, CHECKOUT_ID, PAYMENT_ID, REFUND_AMOUNT, CURRENCY)))
                .isEqualTo("checkout");

        assertThat(base.firstDifferenceFrom(new ApprovalBinding(MERCHANT_ID, MODE,
                PolicyOperation.REFUND_CREATE, null, ApprovalFixtures.OTHER_PAYMENT_ID, REFUND_AMOUNT, CURRENCY)))
                .isEqualTo("payment");
    }

    @Test
    @DisplayName("a missing binding is a difference, not a match")
    void nullBindingIsRefused() {
        assertThat(refundBinding().firstDifferenceFrom(null)).isEqualTo("binding");
    }

    @Test
    @DisplayName("currency case is tolerated — inr and INR are the same currency, not an attack")
    void currencyCaseIsTolerated() {
        ApprovalBinding lowercase = new ApprovalBinding(MERCHANT_ID, MODE, PolicyOperation.REFUND_CREATE,
                null, PAYMENT_ID, REFUND_AMOUNT, "inr");

        assertThat(refundBinding().matches(lowercase)).isTrue();
    }

    @Test
    @DisplayName("an amount has no tolerance at all — one minor unit is a difference")
    void amountHasNoTolerance() {
        assertThat(refundBinding().matches(refundBinding(REFUND_AMOUNT - 1))).isFalse();
        assertThat(refundBinding().matches(refundBinding(REFUND_AMOUNT + 1))).isFalse();
    }

    @Test
    @DisplayName("the binding is projected from the policy request, so approval and execution share one source")
    void bindingIsProjectedFromThePolicyRequest() {
        PolicyRequest request = new PolicyRequest(
                new PolicyRequest.Actor(MERCHANT_ID, MODE, "session-1", "principal"),
                new PolicyRequest.Conversation(CONVERSATION_ID, true, 0, 0, 1),
                "request_refund",
                PolicyOperation.REFUND_CREATE,
                new PolicyRequest.Target(null, null, PAYMENT_ID, REFUND_AMOUNT, CURRENCY));

        assertThat(ApprovalBinding.of(request)).isEqualTo(refundBinding());
    }

    @Test
    @DisplayName("a binding cannot be built without the identity a decision has to be attributable to")
    void missingIdentityIsRejected() {
        assertThatThrownBy(() -> new ApprovalBinding(null, MODE, PolicyOperation.REFUND_CREATE, null,
                PAYMENT_ID, REFUND_AMOUNT, CURRENCY)).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new ApprovalBinding(MERCHANT_ID, MODE, null, null, PAYMENT_ID,
                REFUND_AMOUNT, CURRENCY)).isInstanceOf(NullPointerException.class);
    }

    private static ApprovalBinding with(ApprovalBinding base,
                                        java.util.function.Function<ApprovalBinding, ApprovalBinding> change) {
        return change.apply(base);
    }
}
