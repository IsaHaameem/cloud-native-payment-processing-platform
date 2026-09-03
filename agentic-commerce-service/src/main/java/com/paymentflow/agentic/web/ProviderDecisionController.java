package com.paymentflow.agentic.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.paymentflow.agentic.observability.AgentMetrics;
import com.paymentflow.agentic.provider.PaymentProvider;
import com.paymentflow.agentic.provider.ProviderAuthorizationRequest;
import com.paymentflow.agentic.provider.ProviderDecision;
import com.paymentflow.agentic.provider.ProviderDecisionRecord;
import com.paymentflow.agentic.provider.ProviderDecisionRepository;
import com.paymentflow.common.correlation.CorrelationConstants;
import com.paymentflow.common.exception.UnauthorizedException;
import com.paymentflow.common.security.MerchantContext;
import com.paymentflow.common.security.MerchantContextHolder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The one inbound path from the payment platform: {@code payment-service} asking an external
 * acquirer for a verdict.
 *
 * <pre>
 *   POST /internal/v1/providers/external/decisions
 * </pre>
 *
 * <h2>Two independent authentication checks, neither of which is optional</h2>
 *
 * <ol>
 *   <li>{@code InternalContextFilter} runs ahead of the authorization filter and rejects a
 *       missing, malformed, stale or <b>forged</b> HMAC-signed context with 401 on its own
 *       authority. The controller is never entered.</li>
 *   <li>This controller additionally requires a <em>verified</em> {@link MerchantContext} and
 *       refuses without one. Belt and braces: if a future filter-ordering change ever let an
 *       unauthenticated request through, this still stops it.</li>
 * </ol>
 *
 * <p><b>{@code merchantId} and {@code mode} come from the verified context, never from the
 * request body</b>, and the request shape deliberately has no field for either. A caller cannot
 * ask for a decision on another merchant's behalf, because there is nowhere in the payload to
 * say whose behalf it is.
 *
 * <h2>The contract is provider-agnostic, and stays that way</h2>
 *
 * <p>The request and response mirror the vocabulary sandbox-service already speaks —
 * {@code decisionKey}, {@code operation}, {@code outcome}, {@code declineCode}, {@code errorCode},
 * {@code source} — so payment-service needs no new concept to consume either. <b>The word
 * "Razorpay" appears nowhere in this file's contract</b>, and nothing Razorpay-specific crosses
 * it: {@link ProviderDecision} carries no order id and no Razorpay error taxonomy.
 *
 * <p>{@code source} is the field a reader must not skip. See {@link DecisionResponse#source}.
 */
@RestController
@RequestMapping("/internal/v1/providers/external")
public class ProviderDecisionController {

    private static final Logger log = LoggerFactory.getLogger(ProviderDecisionController.class);

    private final PaymentProvider provider;
    private final AgentMetrics metrics;
    private final ProviderDecisionRepository decisionRepository;

    public ProviderDecisionController(PaymentProvider provider, AgentMetrics metrics,
                                      ProviderDecisionRepository decisionRepository) {
        this.provider = provider;
        this.metrics = metrics;
        this.decisionRepository = decisionRepository;
    }

    /**
     * What payment-service sends.
     *
     * <p>Modelled on {@code SandboxDecisionRequest} field for field, minus the merchant and mode
     * it likewise never carries. {@code @JsonIgnoreProperties} so a caller a version ahead does
     * not get a 400 for sending a field this revision has no use for.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DecisionRequest(
            @NotBlank @Size(max = 128) String decisionKey,
            @NotNull UUID paymentId,
            @NotBlank @Size(max = 32) String operation,
            @Size(max = 128) String paymentMethodToken,
            @Positive long amountMinor,
            @NotBlank @Size(min = 3, max = 3) String currency) {
    }

    /**
     * What payment-service gets back.
     *
     * @param source <b>how the verdict was arrived at, and the field that carries the honesty of
     *               this whole integration.</b> {@code payment_collected} means a cardholder
     *               authorized something. {@code order_accepted} on an {@code APPROVE} means an
     *               order was accepted and <em>no cardholder authorized anything</em> — it is a
     *               demonstration stand-in, never a real card payment
     * @param demo   {@code true} only for that stand-in. Anything reporting an approval to a
     *               person must show this
     */
    public record DecisionResponse(
            String outcome,
            String declineCode,
            String errorCode,
            String source,
            String providerReference,
            boolean demo) {

        static DecisionResponse of(ProviderDecision decision) {
            return new DecisionResponse(decision.outcome().name(), decision.declineCode(),
                    decision.errorCode(), decision.source(), decision.providerReference(),
                    decision.demo());
        }
    }

    @PostMapping("/decisions")
    public DecisionResponse decide(@Valid @RequestBody DecisionRequest request) {
        MerchantContext context = MerchantContextHolder.get()
                .orElseThrow(() -> new UnauthorizedException(
                        "A verified internal context is required to request a provider decision."));

        String correlationId = MDC.get(CorrelationConstants.CORRELATION_ID_MDC_KEY);
        log.info("provider decision requested merchant={} mode={} payment={} operation={} correlation_id={}",
                context.merchantId(), context.mode(), request.paymentId(), request.operation(),
                correlationId);

        ProviderDecision decision = provider.authorize(new ProviderAuthorizationRequest(
                request.decisionKey(), request.paymentId(), request.operation(),
                request.paymentMethodToken(), request.amountMinor(), request.currency()));

        metrics.providerDecision(provider.providerName(), decision.outcome().name(),
                decision.source(), decision.demo());

        persist(context, request, decision, correlationId);

        if (decision.isDemoApproval()) {
            log.warn("Returning a DEMO approval for payment {}: no cardholder authorized this, and no "
                            + "money has moved. source={}",
                    request.paymentId(), decision.source());
        }
        return DecisionResponse.of(decision);
    }

    /**
     * Keeps the verdict (G-6), so a merchant can later see whether a payment the agent made was
     * backed by a real cardholder authorization or by a demonstration stand-in. Idempotent on the
     * decision key payment-service supplies per attempt; a failure to persist never fails the
     * decision — the answer payment-service is waiting for is what matters, and the record is an
     * observability aid, not part of the money path.
     */
    private void persist(MerchantContext context, DecisionRequest request, ProviderDecision decision,
                         String correlationId) {
        try {
            if (decisionRepository.existsByDecisionKey(request.decisionKey())) {
                return;
            }
            decisionRepository.save(ProviderDecisionRecord.of(
                    context.merchantId(), context.mode(), request.paymentId(), request.decisionKey(),
                    request.operation(), decision, provider.providerName(), request.amountMinor(),
                    request.currency(), correlationId));
        } catch (RuntimeException e) {
            // A race on the unique key, or any other write failure. Logged, not propagated.
            log.warn("Could not persist the provider decision for payment {} (key {}): {}",
                    request.paymentId(), request.decisionKey(), e.getClass().getSimpleName());
        }
    }
}
