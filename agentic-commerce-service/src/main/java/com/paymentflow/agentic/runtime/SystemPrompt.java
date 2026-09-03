package com.paymentflow.agentic.runtime;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The agent's system prompt, loaded once from a versioned resource.
 *
 * <h2>Why a resource file and not a string constant</h2>
 *
 * <p>The prompt is behaviour, and it is behaviour a non-Java reader needs to be able to audit.
 * Someone asking "what is this agent told about refunds?" should be able to read the answer
 * without opening a class file, and someone changing it should produce a diff that reads as
 * prose rather than as an escaped string literal.
 *
 * <h2>Why the version is recorded on every action</h2>
 *
 * <p>{@link #VERSION} is written to {@code agent_actions.prompt_version}. Prompts change, and
 * an action taken under an older one stays interpretable only if the row says which one was in
 * force — the same reasoning that puts a policy version on every persisted decision. Without
 * it, "why did the agent do that?" is unanswerable a month later.
 *
 * <p><b>The version must change whenever the file does.</b> A new prompt under an old version
 * number is worse than no version at all, because it makes the trail confidently wrong.
 *
 * <h2>What the prompt is and is not</h2>
 *
 * <p>It is an instruction to a model, which is to say a strong suggestion. <b>No financial
 * bound is stated in it, and none should be.</b> Caps, budgets and approval thresholds live in
 * {@code paymentflow.agentic.policy} and are enforced by {@code PolicyEngine} against
 * server-side facts. Putting a number in the prompt would create a second, weaker copy of a
 * rule — one a persuasive conversation could talk past — and the first thing anyone would
 * notice is the two copies disagreeing.
 *
 * <p>What the prompt does buy is a better-behaved agent and clearer conversations: a model
 * told not to invent a payment status mostly does not, which means fewer refusals to explain
 * and a demo that reads honestly. The enforcement underneath is what makes it safe; the prompt
 * is what makes it pleasant.
 */
@Component
public class SystemPrompt {

    /**
     * The prompt revision, recorded on every action taken under it.
     *
     * <p>Bump this in the same commit that edits the resource, always.
     */
    public static final String VERSION = "v1";

    private static final String RESOURCE_PATH = "prompts/agent-system-prompt-v1.md";

    private final String text;

    public SystemPrompt() {
        this.text = load();
    }

    /** The prompt text, identical on every call — it is read once at startup and never re-read. */
    public String text() {
        return text;
    }

    public String version() {
        return VERSION;
    }

    private static String load() {
        try {
            return new ClassPathResource(RESOURCE_PATH).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Fail fast, loudly, at startup. An agent running without its system prompt is not a
            // degraded agent — it is a different one, with none of the behaviour anyone reviewed.
            throw new UncheckedIOException(
                    "The agent system prompt (" + RESOURCE_PATH + ") could not be read. The agent runtime "
                            + "must not start without it.", e);
        }
    }
}
