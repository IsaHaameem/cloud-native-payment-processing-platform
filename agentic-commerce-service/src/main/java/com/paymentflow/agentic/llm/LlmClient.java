package com.paymentflow.agentic.llm;

/**
 * The seam between the agent runtime and whatever model is behind it.
 *
 * <p>The runtime depends on this interface and on nothing provider-specific — no Anthropic
 * type, no provider SDK, no wire shape. Swapping the provider is writing one more
 * implementation of these three methods; it touches no policy code, no tool, and no part of
 * the pipeline that decides whether money moves.
 *
 * <p>That replaceability is worth more here than it usually is, because of what the model
 * <em>cannot</em> do in this design. It cannot decide an amount, a policy outcome, an approval,
 * or a payment status — all four are server-side facts it is merely shown. So the provider is
 * genuinely a component rather than the system, and the interface can afford to be this small.
 *
 * <p><b>Implementations must never log, echo, or serialise their credential.</b> The key is a
 * transport header and nothing else: it does not appear on {@link LlmRequest}, in a prompt, in
 * an exception message, or in a metric tag.
 */
public interface LlmClient {

    /** Which implementation is in force, for logging and for the health of a demo nobody can debug blind. */
    String providerName();

    /**
     * Whether this client can currently serve a request.
     *
     * <p>Checked before a turn starts so an unconfigured or unreachable provider produces a
     * clean, explainable failure rather than an exception from inside the loop with a
     * half-written action trail behind it.
     */
    boolean isAvailable();

    /**
     * One completion.
     *
     * @throws LlmUnavailableException     if the provider could not be reached, timed out, or
     *                                     returned a server error
     * @throws MalformedLlmOutputException if the provider answered with something this adapter
     *                                     cannot read as a well-formed response. <b>Never
     *                                     repaired by guesswork</b> — see the runtime's handling
     */
    LlmResponse complete(LlmRequest request);
}
