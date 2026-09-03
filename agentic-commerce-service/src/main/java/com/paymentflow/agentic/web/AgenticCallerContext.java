package com.paymentflow.agentic.web;

import com.paymentflow.agentic.config.AgenticProperties;
import com.paymentflow.common.exception.ForbiddenException;
import com.paymentflow.common.exception.UnauthorizedException;
import com.paymentflow.common.security.InternalPrincipal;
import com.paymentflow.common.security.MerchantContext;
import com.paymentflow.common.security.MerchantContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Resolves who a {@code /api/agentic/**} request is acting for, from the HMAC-verified internal
 * context and nothing else.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Before the developer portal put a proxy in front of it, this API took its merchant from
 * {@code paymentflow.agentic.demo.merchant-id} — a single configured tenant, with no
 * authentication at all ({@code SecurityConfig}'s recorded limitation). The portal proxy signs
 * an internal context derived from the authenticated session (merchant, mode, user) exactly the
 * way the gateway does for {@code /v1}, and {@code common-lib}'s {@code InternalContextFilter}
 * verifies it. This class reads the result.
 *
 * <p><b>Merchant and mode come from the verified context, never from the request.</b> There is
 * no body field, query parameter or header a caller can set to choose either — the same
 * property {@link ProviderDecisionController} relies on.
 *
 * <h2>The two guards</h2>
 *
 * <ol>
 *   <li><b>Test mode only.</b> This extension is test-mode by construction — the schema enforces
 *       it and the policy engine enforces it again. A context asserting {@code live} is refused
 *       here rather than allowed to reach a policy check that would refuse it anyway.</li>
 *   <li><b>One merchant per deployment.</b> The service holds exactly one platform API key
 *       ({@code AGENTIC_PLATFORM_API_KEY}), and that key belongs to one merchant. A session for
 *       any other merchant is refused, because honouring it would mean the agent acting for
 *       merchant A using merchant B's credential — the action log would say one thing and the
 *       gateway would do another. When no demo merchant is configured the context's merchant is
 *       used as-is and money actions fail later with {@code PLATFORM_NOT_CONFIGURED}.</li>
 * </ol>
 */
@Component
public class AgenticCallerContext {

    /** This extension is test-mode only, enforced in the schema and again in the policy engine. */
    static final String MODE = "test";

    private final AgenticProperties properties;

    public AgenticCallerContext(AgenticProperties properties) {
        this.properties = properties;
    }

    /** The resolved caller: the merchant to act for, the mode (always {@code test}), and an audit actor. */
    public record Caller(UUID merchantId, String mode, String actor) {
    }

    /**
     * @throws UnauthorizedException no verified internal context is present — the request was not
     *                               signed, or its signature did not check out (the filter has
     *                               usually already answered 401 in that case)
     * @throws ForbiddenException    the context asserts a non-test mode, or a merchant this
     *                               deployment is not bound to
     */
    public Caller resolve() {
        MerchantContext context = MerchantContextHolder.get().orElseThrow(() -> new UnauthorizedException(
                "This request needs a verified portal context. The agentic API is reachable only "
                        + "through the authenticated developer portal."));

        if (!MODE.equalsIgnoreCase(context.mode())) {
            throw new ForbiddenException(
                    "The agentic commerce extension operates in test mode only.");
        }

        String configuredMerchant = properties.demo().merchantId();
        if (configuredMerchant != null && !configuredMerchant.isBlank()) {
            UUID boundMerchant = UUID.fromString(configuredMerchant.trim());
            if (!boundMerchant.equals(context.merchantId())) {
                throw new ForbiddenException(
                        "This agent instance is bound to a different merchant than the one in your session.");
            }
        }

        return new Caller(context.merchantId(), MODE, actorOf(context));
    }

    /**
     * A stable, non-secret description of who acted, written to {@code policy_decisions.actor}
     * and the action trail. A session names its user; an API key names its key. Neither value
     * is a credential.
     */
    private static String actorOf(MerchantContext context) {
        if (context.principal() == InternalPrincipal.SESSION) {
            return "session-user:" + context.userId();
        }
        return "api-key:" + context.keyId();
    }
}
