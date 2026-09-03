package com.paymentflow.agentic.policy;

import com.paymentflow.agentic.checkout.CheckoutStatus;

import java.util.Objects;
import java.util.UUID;

/**
 * Everything the policy engine is allowed to know, as one typed value.
 *
 * <p><b>There is no field on this record that a model can write.</b> That is the entire
 * design. The engine is handed an amount the checkout derived, a currency the catalogue set,
 * a checkout state read from its row, counters maintained by the conversation, and an actor
 * established by the caller's own credential. Free text from the model reaches this class
 * nowhere — not as a hint, not as a reason, not as a metadata blob — so there is no path by
 * which a persuasive sentence in a chat window can move a threshold.
 *
 * <p>It is a record, and immutable, for the same reason: a decision must be reproducible from
 * the facts it was made on, and a request that could be mutated between evaluation and
 * logging would make the logged row a claim rather than evidence.
 *
 * @param toolName  the registry's stable name for the tool, recorded on the decision. Rules
 *                  branch on {@code operation}, never on this string.
 * @param operation what the tool will do, and the thing an approval binds to. {@code null}
 *                  means the tool was not recognised, which {@link PolicyRule#TOOL_ALLOW_LIST}
 *                  refuses.
 */
public record PolicyRequest(
        Actor actor,
        Conversation conversation,
        String toolName,
        PolicyOperation operation,
        Target target) {

    public PolicyRequest {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(target, "target");
    }

    /** The tool's risk class, read off the operation so the two can never disagree. */
    public ToolCategory category() {
        return operation == null ? null : operation.category();
    }

    /**
     * Who is acting, and inside which tenant.
     *
     * @param merchantId the merchant every resource in this action must belong to. Ownership
     *                   itself is enforced where the resources are read — every repository in
     *                   this service is {@code (merchantId, mode)}-scoped and 404s anything
     *                   else — so the engine records the tenant rather than re-deriving it.
     * @param mode       {@code test}, always. Refused otherwise, structurally rather than by
     *                   convention.
     * @param principal  the identity written to {@code policy_decisions.actor}.
     */
    public record Actor(UUID merchantId, String mode, String sessionRef, String principal) {

        public Actor {
            Objects.requireNonNull(merchantId, "merchantId");
            Objects.requireNonNull(mode, "mode");
        }

        public boolean isIdentified() {
            return principal != null && !principal.isBlank();
        }
    }

    /**
     * The conversation's server-side counters, as they stand before this action.
     *
     * <p>Maintained on the {@code conversations} row rather than recomputed from the action
     * log, because a budget check runs before every money action and must not become a scan.
     *
     * @param spentMinor     cumulative amount already committed to payments in this conversation
     * @param refundedMinor  cumulative amount already refunded in this conversation
     * @param toolCallCount  tool calls made in this conversation <b>including the one being
     *                       evaluated</b>. The counter advances when a call is attempted — a
     *                       runaway agent looping on rejections is exactly what the ceiling
     *                       exists to stop, so refused attempts have to count too
     */
    public record Conversation(UUID id, boolean active, long spentMinor, long refundedMinor, int toolCallCount) {

        public Conversation {
            Objects.requireNonNull(id, "id");
            if (spentMinor < 0 || refundedMinor < 0 || toolCallCount < 0) {
                throw new IllegalArgumentException("Conversation counters cannot be negative.");
            }
        }
    }

    /**
     * What the action will act on, and for how much — all of it resolved server-side.
     *
     * @param amountMinor    the amount in the currency's minor unit, derived from the checkout
     *                       total or from the payment being refunded. {@code null} for a tool
     *                       that moves no money; {@code null} on one that does is refused by
     *                       {@link PolicyRule#AMOUNT_RESOLVED}.
     * @param checkoutStatus the checkout's state as read from its own row, not as reported by
     *                       anyone
     */
    public record Target(
            UUID checkoutId,
            CheckoutStatus checkoutStatus,
            UUID paymentId,
            Long amountMinor,
            String currency) {

        /** A target for a tool that touches nothing financial. */
        public static Target none() {
            return new Target(null, null, null, null, null);
        }

        public static Target ofCheckout(UUID checkoutId, CheckoutStatus status, long amountMinor, String currency) {
            return new Target(checkoutId, status, null, amountMinor, currency);
        }

        public static Target ofPayment(UUID paymentId, long amountMinor, String currency) {
            return new Target(null, null, paymentId, amountMinor, currency);
        }

        public boolean hasResolvedAmount() {
            return amountMinor != null && amountMinor > 0;
        }
    }
}
