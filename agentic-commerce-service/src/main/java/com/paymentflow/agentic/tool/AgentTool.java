package com.paymentflow.agentic.tool;

/**
 * One thing the agent is permitted to do.
 *
 * <p>The interface has four members because the pipeline has four stages, and keeping them
 * apart is the point of the whole design:
 *
 * <pre>
 *   spec()      what the model is shown, and how policy classifies this tool
 *   validate()  the model's arguments become a typed input, or the call is rejected
 *   resolve()   server-side facts are read; the AMOUNT comes into existence here
 *   execute()   ────────── only reachable after policy, and after approval if required
 * </pre>
 *
 * <p><b>{@link #execute} is never called by a tool's own {@code resolve}, and no tool calls
 * another tool.</b> The orchestrator runs the stages in order, evaluates policy between
 * resolve and execute, and holds a money action at an approval if the verdict says so. A tool
 * that could shortcut that would be a tool that had its own policy.
 *
 * <p><b>What no implementation of this interface may be.</b> There is no tool that takes a
 * URL, a host, a header, a request body, a shell command, a file path or a SQL string — not
 * as an argument, not as an option, not behind a flag. The model's reach is exactly the set
 * of typed operations declared by the implementations registered with {@link ToolRegistry},
 * and that registry refuses to start if a tool tries to be one of those things.
 *
 * @param <I> the tool's typed input, produced by {@link #validate} and consumed by the rest.
 *            A record per tool, so an argument that was never validated cannot reach
 *            execution by being read out of a map a second time
 */
public interface AgentTool<I> {

    /** Stable name, description, policy classification and input schema. Constant per tool. */
    ToolSpec spec();

    /**
     * Turns raw model arguments into this tool's typed input.
     *
     * @throws com.paymentflow.agentic.error.AgenticException with
     *         {@code TOOL_ARGUMENTS_INVALID} if the arguments do not satisfy {@link #spec}'s
     *         schema. Implementations start with
     *         {@link ToolArguments#requireOnly(ToolSchema)} so an unlisted field is a
     *         rejection rather than a silent drop
     */
    I validate(ToolArguments arguments);

    /**
     * Reads the server-side facts this action depends on and states what it would do.
     *
     * <p>Must not move money, and must not mutate anything a later refusal would have to undo.
     * It runs <em>before</em> the policy engine has said anything, and a resolve with side
     * effects would mean a refused action had already changed the world.
     *
     * <p>Creating or locking a checkout is the one deliberate exception, and it belongs to the
     * tools that own that lifecycle: locking is what freezes the amount policy is about to
     * evaluate, and the lock is released when the attempt does not proceed.
     */
    ResolvedAction resolve(ToolContext context, I input);

    /**
     * Performs the action.
     *
     * <p>Reached only after the policy engine returned {@code PERMIT} — directly, or by way of
     * an approval that was granted and redeemed against this exact resolution.
     *
     * @param resolved the same resolution policy decided on. Passed in rather than recomputed
     *                 so that what executes is what was approved, not a fresh read that may
     *                 have moved
     */
    ToolResult execute(ToolContext context, I input, ResolvedAction resolved);
}
