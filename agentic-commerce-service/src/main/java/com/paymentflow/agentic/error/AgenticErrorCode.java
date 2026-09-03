package com.paymentflow.agentic.error;

import com.paymentflow.common.dto.error.ErrorType;
import com.paymentflow.common.error.ErrorCode;

/**
 * This service's own domain error codes.
 *
 * <p>Declared here rather than added to {@code CommonErrorCode} for a specific reason:
 * {@code ErrorCatalogue} is the published catalogue of codes the <em>public {@code /v1}
 * tier</em> can return, and {@code ErrorCatalogueDocumentationConsistencyTest} asserts it
 * against {@code docs/ERRORS.md} in both directions. None of these codes appears on
 * {@code /v1} — they belong to a hackathon surface that is deliberately outside the
 * published contract (AD-8) — so registering them would be promising something the platform
 * does not offer, and would fail that test besides.
 *
 * <p>{@code ErrorCode} being an interface is exactly what makes this possible; its javadoc
 * anticipates services declaring their own.
 */
public enum AgenticErrorCode implements ErrorCode {

    // ── Checkout ────────────────────────────────────────────────────────────────────────
    CHECKOUT_NOT_OPEN(409, ErrorType.INVALID_REQUEST_ERROR,
            "This checkout is no longer open for modification."),
    CHECKOUT_EXPIRED(409, ErrorType.INVALID_REQUEST_ERROR,
            "This checkout has expired. Create a new one."),
    CHECKOUT_ALREADY_PAID(409, ErrorType.INVALID_REQUEST_ERROR,
            "This checkout has already been paid."),
    CHECKOUT_EMPTY(400, ErrorType.INVALID_REQUEST_ERROR,
            "A checkout must contain at least one line item before it can be paid."),
    CHECKOUT_CURRENCY_MISMATCH(400, ErrorType.INVALID_REQUEST_ERROR,
            "Every item in a checkout must share one currency."),
    INSUFFICIENT_INVENTORY(409, ErrorType.INVALID_REQUEST_ERROR,
            "There is not enough inventory to satisfy this request."),

    // ── Tools and policy ────────────────────────────────────────────────────────────────
    UNKNOWN_TOOL(400, ErrorType.INVALID_REQUEST_ERROR,
            "No such tool is registered."),
    TOOL_ARGUMENTS_INVALID(400, ErrorType.INVALID_REQUEST_ERROR,
            "The tool arguments did not satisfy the tool's schema."),
    /**
     * The single most important code in this enum. It is what a refused money action returns,
     * and the reason it is distinct from {@code FORBIDDEN} is that the remedy differs: a
     * permission error means "use a different credential", this means "this action is outside
     * what the agent is allowed to do at all, whoever is asking".
     */
    POLICY_REFUSED(403, ErrorType.PERMISSION_ERROR,
            "The policy engine refused this action."),
    APPROVAL_REQUIRED(409, ErrorType.INVALID_REQUEST_ERROR,
            "This action requires human approval before it can execute."),
    APPROVAL_NOT_PENDING(409, ErrorType.INVALID_REQUEST_ERROR,
            "This approval is no longer awaiting a decision."),
    APPROVAL_EXPIRED(409, ErrorType.INVALID_REQUEST_ERROR,
            "This approval request has expired."),
    /**
     * Raised when the amount an approval was granted for no longer matches the amount that
     * would actually be charged. An approval authorises a number, not an intention.
     */
    APPROVAL_AMOUNT_CHANGED(409, ErrorType.INVALID_REQUEST_ERROR,
            "The amount has changed since this approval was requested."),

    // ── Conversation ────────────────────────────────────────────────────────────────────
    CONVERSATION_CLOSED(409, ErrorType.INVALID_REQUEST_ERROR,
            "This conversation is closed."),
    TOOL_BUDGET_EXHAUSTED(429, ErrorType.RATE_LIMIT_ERROR,
            "This conversation has used its allowance of tool calls."),

    // ── Downstream ──────────────────────────────────────────────────────────────────────
    PLATFORM_UNAVAILABLE(503, ErrorType.API_ERROR,
            "The payment platform could not be reached."),
    /**
     * Configuration is missing or placeholder. Distinct from {@link #PLATFORM_UNAVAILABLE} so
     * that an operator reading a log line learns which of the two very different remedies
     * applies, without anyone having to log the value that was missing.
     */
    PLATFORM_NOT_CONFIGURED(503, ErrorType.API_ERROR,
            "This service has no payment-platform credential configured."),
    PROVIDER_UNAVAILABLE(503, ErrorType.API_ERROR,
            "The payment provider could not be reached."),
    PROVIDER_NOT_CONFIGURED(503, ErrorType.API_ERROR,
            "The payment provider has no credential configured."),
    LLM_UNAVAILABLE(503, ErrorType.API_ERROR,
            "The language model could not be reached."),
    /**
     * The model answered, but with something the adapter cannot read as a well-formed response.
     *
     * <p>Distinct from {@link #LLM_UNAVAILABLE} because the remedies differ completely:
     * unreachable means try again, malformed means this service and the provider disagree about
     * the contract. Collapsing the two would send an operator into a retry loop against a
     * provider that is answering every time.
     */
    LLM_OUTPUT_INVALID(502, ErrorType.API_ERROR,
            "The language model returned a response that could not be understood."),
    /**
     * A turn hit its configured ceiling on tool calls, iterations or wall-clock time.
     *
     * <p>Not an error in the usual sense — it is the bound working. Given a code of its own so
     * a caller can tell "the agent stopped because it was told to" apart from "the agent
     * broke", which are very different things to show a buyer.
     */
    AGENT_LIMIT_REACHED(200, ErrorType.INVALID_REQUEST_ERROR,
            "The assistant reached the limit of what it may do in one turn.");

    private final int httpStatus;
    private final ErrorType type;
    private final String defaultMessage;

    AgenticErrorCode(int httpStatus, ErrorType type, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.type = type;
        this.defaultMessage = defaultMessage;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }

    @Override
    public ErrorType type() {
        return type;
    }
}
