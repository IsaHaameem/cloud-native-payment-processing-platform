package com.paymentflow.sandbox.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One entry of the seeded test-card catalogue (M17), as an integrator reads it.
 *
 * <p>The catalogue is the sandbox's contract: every field here answers "what will happen if
 * I authorize against this token?", which is the only reason to read it. The prose (M21.7)
 * matters more on this resource than on most — a developer meets it before they have made a
 * single successful call, and an undocumented `captureBehaviour` is the difference between
 * choosing the right token and guessing.
 */
public record TestCardResponse(
        @Schema(description = """
                The token to pass as `paymentMethodToken` when creating a payment. This is \
                what selects the behaviour described by the rest of this object.""",
                example = "tok_visa_approved")
        String token,

        @Schema(description = "The card brand this token simulates.", example = "visa")
        String brand,

        @Schema(description = """
                What authorizing against this token does: approve, decline, or fail with an \
                error. A decline is the acquirer saying no and is a normal outcome your \
                integration must handle; an error is the acquirer being unreachable or \
                broken.""",
                example = "APPROVE")
        String outcome,

        // `types` rather than a plain type on the three nullable fields below, and the
        // wording is "null" rather than "absent" — this record carries no
        // @JsonInclude(NON_NULL), so an approving card really does serialize
        // `"declineCode": null`. M21.7's contract test caught the document claiming
        // otherwise; declaring the truth is additive, whereas suppressing the nulls would
        // change the wire.
        @Schema(description = "The decline code the authorization will carry, when `outcome` "
                + "is a decline. **Null** otherwise.",
                types = {"string", "null"}, example = "insufficient_funds")
        String declineCode,

        @Schema(description = "The error code the authorization will fail with, when "
                + "`outcome` is an error. **Null** otherwise.",
                types = {"string", "null"}, example = "processor_unavailable")
        String errorCode,

        @Schema(description = """
                How long the simulated acquirer will take to answer, in milliseconds. Non-zero \
                on the tokens that exist to let you exercise timeouts deliberately rather \
                than by waiting for a bad day.""",
                example = "0")
        int latencyMs,

        @Schema(description = """
                What a later capture against this token will do — succeed immediately, fail, \
                or settle after a delay. Authorization and capture can behave differently on \
                purpose: a card that authorizes cleanly and then fails to capture is a real \
                and easily-missed case.""",
                example = "IMMEDIATE")
        String captureBehaviour,

        @Schema(description = "What a later refund against this token will do.",
                example = "IMMEDIATE")
        String refundBehaviour,

        @Schema(description = """
                How long a deferred capture or refund waits before its outcome arrives, in \
                milliseconds. **Null** on the tokens whose behaviour is immediate; where it \
                is set, the result reaches you as a webhook, exactly as an asynchronous \
                settlement would.""",
                types = {"integer", "null"}, format = "int32", example = "5000")
        Integer deferredDelayMs,

        @Schema(description = "What this token is for, in one line.",
                example = "Always approves; captures and refunds immediately.")
        String description) {
}
