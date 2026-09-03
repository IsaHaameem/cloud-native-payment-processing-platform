package com.paymentflow.agentic.provider.razorpay;

import com.paymentflow.agentic.config.AgenticProperties;
import com.paymentflow.agentic.provider.PaymentProvider;
import com.paymentflow.agentic.provider.ProviderAuthorizationRequest;
import com.paymentflow.agentic.provider.ProviderDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The Razorpay adapter. <b>Read the next section before trusting any approval this produces.</b>
 *
 * <h2>The verified fact this whole class is shaped around</h2>
 *
 * <p><b>Razorpay has no server-to-server "authorize this card now" operation.</b> There is no
 * endpoint this service can call that takes an instrument and returns an authorization. The real
 * flow is:
 *
 * <pre>
 *   server creates an order   →   cardholder authorizes it in Checkout, client-side   →   server reads the payment
 * </pre>
 *
 * <p>So when payment-service asks this adapter to authorize something, the adapter can create an
 * order and then look to see whether a cardholder has authorized it. If one has, that is a real
 * authorization and it is reported as one. If one has not — which, in a server-to-server demo
 * with no browser in the loop, is the normal case — then <b>there is no authorization to
 * report</b>, and something has to decide what to say.
 *
 * <h2>What {@code uncollected-order-outcome} decides</h2>
 *
 * <dl>
 *   <dt>{@code decline} — the default, and the honest answer</dt>
 *   <dd>The payment fails with {@code razorpay_payment_not_collected}. The platform records a
 *       failed payment, the agent explains it, and nobody is told that money moved.</dd>
 *
 *   <dt>{@code approve} — demonstration only</dt>
 *   <dd>Provider acceptance of the <em>order</em> is treated as standing in for an
 *       authorization. The decision carries {@link ProviderDecision#SOURCE_ORDER_ACCEPTED} and
 *       {@code demo = true}, and every approval taken this way is logged at WARN saying plainly
 *       that no cardholder authorized anything.</dd>
 * </dl>
 *
 * <p><b>An approval sourced {@code order_accepted} is not a successful card payment and must
 * never be described as one.</b> It means Razorpay accepted an order for an amount. Nothing was
 * charged, no instrument was verified, and no cardholder was present. The setting exists so a
 * demo can show the full lifecycle end to end; it is off by default, and turning it on is a
 * decision to show a stand-in.
 *
 * <h2>Scope</h2>
 *
 * <p>Authorization only. No capture, no refund, no webhook, no settlement — Depth 1 as approved,
 * and deliberately not one endpoint more. Everything Razorpay-specific stops in this package:
 * {@link ProviderDecision} carries no order id, no Razorpay status and no Razorpay error
 * taxonomy, so {@code payment-service} never learns the provider's name.
 */
@Component
public class RazorpayPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(RazorpayPaymentProvider.class);

    static final String PROVIDER_NAME = "razorpay";

    /**
     * What a payment fails with when an order exists but no cardholder authorized it.
     *
     * <p>Its own code rather than a generic decline, because the remedy is completely different:
     * an ordinary decline means try another card, this means complete the provider's checkout.
     * {@code ExplainPaymentOutcomeTool} knows this code and explains exactly that.
     */
    static final String DECLINE_NOT_COLLECTED = "razorpay_payment_not_collected";

    /** The order was created but Razorpay returned no usable identifier for it. */
    static final String ERROR_ORDER_NOT_CREATED = "razorpay_order_not_created";

    static final String ERROR_UNAVAILABLE = "razorpay_unavailable";
    static final String ERROR_REJECTED = "razorpay_rejected_request";

    private final RazorpayClient client;
    private final AgenticProperties properties;

    public RazorpayPaymentProvider(RazorpayClient client, AgenticProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean isConfigured() {
        return properties.razorpay().isConfigured();
    }

    /**
     * Creates an order, then reports whatever a cardholder has actually authorized against it.
     *
     * <p>Never throws. Every failure becomes a decision the platform can record, because the
     * caller is answering payment-service over HTTP and an exception there would become an
     * opaque 500 where a verdict belongs.
     */
    @Override
    public ProviderDecision authorize(ProviderAuthorizationRequest request) {
        if (!isConfigured()) {
            // Refused before anything is sent. An unconfigured adapter that called anyway would
            // put a placeholder credential on the wire and into somebody's proxy log, and would
            // then report a 401 that sends an operator looking in the wrong place.
            log.warn("Razorpay is not configured; declining to attempt an authorization for payment {}.",
                    request.paymentId());
            return ProviderDecision.error(ERROR_UNAVAILABLE, ProviderDecision.SOURCE_NOT_CONFIGURED);
        }

        try {
            RazorpayClient.RazorpayOrder order = client.createOrder(
                    request.amountMinor(), request.currency(), receiptFor(request), notesFor(request));

            if (order == null || order.id() == null || order.id().isBlank()) {
                return ProviderDecision.error(ERROR_ORDER_NOT_CREATED,
                        ProviderDecision.SOURCE_PROVIDER_UNAVAILABLE);
            }
            return decideFromCollectedPayments(order, request);

        } catch (RazorpayRequestException e) {
            // A verdict about the request. Reported with the provider's own code, not retried.
            log.info("Razorpay rejected an authorization request for payment {}: status={} code={}",
                    request.paymentId(), e.httpStatus(), e.providerCode());
            return ProviderDecision.error(
                    e.providerCode() == null ? ERROR_REJECTED : e.providerCode().toLowerCase(Locale.ROOT),
                    ProviderDecision.SOURCE_PROVIDER_UNAVAILABLE);

        } catch (RazorpayUnavailableException e) {
            log.warn("Razorpay was unavailable while authorizing payment {}.", request.paymentId(), e);
            return ProviderDecision.error(ERROR_UNAVAILABLE, ProviderDecision.SOURCE_PROVIDER_UNAVAILABLE);

        } catch (RuntimeException e) {
            // Anything unforeseen, including a circuit breaker that is open. Reported as an
            // error rather than allowed to propagate: a provider adapter that throws leaves the
            // payment in an unknown state, which is the one outcome worse than a declined one.
            log.error("An unexpected failure occurred while authorizing payment {} with Razorpay.",
                    request.paymentId(), e);
            return ProviderDecision.error(ERROR_UNAVAILABLE, ProviderDecision.SOURCE_PROVIDER_UNAVAILABLE);
        }
    }

    /**
     * The heart of it: what did a cardholder actually do?
     *
     * <p>A payment in {@code authorized} or {@code captured} is a real authorization and is
     * reported as one. A payment in {@code failed} is a real decline, carrying the acquirer's own
     * reason. Anything else — including no payments at all — means nobody authorized anything,
     * and {@link #decideUncollected} handles that case.
     */
    private ProviderDecision decideFromCollectedPayments(RazorpayClient.RazorpayOrder order,
                                                         ProviderAuthorizationRequest request) {
        List<RazorpayClient.RazorpayPayment> payments = client.orderPayments(order.id());

        Optional<RazorpayClient.RazorpayPayment> authorized = payments.stream()
                .filter(RazorpayClient.RazorpayPayment::isAuthorized)
                .findFirst();
        if (authorized.isPresent()) {
            log.info("Razorpay reports a collected authorization for payment {} (order {}).",
                    request.paymentId(), order.id());
            return ProviderDecision.approved(ProviderDecision.SOURCE_PAYMENT_COLLECTED,
                    authorized.get().id());
        }

        Optional<RazorpayClient.RazorpayPayment> failed = payments.stream()
                .filter(RazorpayClient.RazorpayPayment::isFailed)
                .findFirst();
        if (failed.isPresent()) {
            RazorpayClient.RazorpayPayment payment = failed.get();
            String declineCode = payment.errorCode() == null
                    ? "razorpay_payment_failed"
                    : payment.errorCode().toLowerCase(Locale.ROOT);
            log.info("Razorpay reports a failed cardholder payment for payment {} (order {}): {}",
                    request.paymentId(), order.id(), declineCode);
            return ProviderDecision.declined(declineCode, ProviderDecision.SOURCE_PAYMENT_COLLECTED,
                    payment.id());
        }

        return decideUncollected(order, request);
    }

    /**
     * An order exists and nobody has authorized it. What the configuration says to do.
     *
     * <p>The default is to decline, and it is the default because it is true: there is no
     * authorization, so reporting one would be a lie the audit trail would carry forever.
     */
    private ProviderDecision decideUncollected(RazorpayClient.RazorpayOrder order,
                                               ProviderAuthorizationRequest request) {
        if (!properties.razorpay().treatsUncollectedOrderAsApproved()) {
            log.info("Razorpay order {} was created for payment {} but no cardholder payment was "
                            + "collected; declining with {}.",
                    order.id(), request.paymentId(), DECLINE_NOT_COLLECTED);
            return ProviderDecision.declined(DECLINE_NOT_COLLECTED,
                    ProviderDecision.SOURCE_ORDER_ACCEPTED, order.id());
        }

        // WARN, every time, with the words spelled out. An operator who enabled this setting
        // months ago and has forgotten should be reminded by the log that these approvals are
        // not payments.
        log.warn("DEMO APPROVAL: Razorpay order {} was accepted for payment {}, but NO CARDHOLDER "
                        + "AUTHORIZED IT. This approval is a demonstration stand-in "
                        + "(source={}), not a real card payment, and no money has moved. "
                        + "Set razorpay.uncollected-order-outcome=decline to stop producing these.",
                order.id(), request.paymentId(), ProviderDecision.SOURCE_ORDER_ACCEPTED);
        return ProviderDecision.demoApproved(order.id());
    }

    /** The platform's payment id, so an order can be traced back without a shared database. */
    private static String receiptFor(ProviderAuthorizationRequest request) {
        return "pf_" + request.paymentId();
    }

    /**
     * Identifiers only.
     *
     * <p>Notes are merchant-visible in Razorpay's own dashboard, so nothing goes in them that is
     * not already an identifier this service is happy to see there.
     */
    private static Map<String, String> notesFor(ProviderAuthorizationRequest request) {
        Map<String, String> notes = new LinkedHashMap<>();
        notes.put("paymentflow_payment_id", request.paymentId().toString());
        notes.put("paymentflow_operation", request.operation());
        return notes;
    }
}
