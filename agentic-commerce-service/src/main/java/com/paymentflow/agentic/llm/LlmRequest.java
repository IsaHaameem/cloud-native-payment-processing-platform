package com.paymentflow.agentic.llm;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One call to a model: the system prompt, the conversation so far, and the tools it may use.
 *
 * <p><b>The tool definitions are not built here.</b> They come from {@code ToolRegistry}, which
 * is the single source of truth for what exists and what each one accepts — and which orders
 * them by name so this request renders byte-identically across runs. Assembling them here
 * would create a second place where the model's capabilities are described, and the second
 * place is the one that goes stale.
 *
 * <p><b>No credential appears on this record.</b> Not the platform key, not the provider key,
 * not the internal HMAC secret, not a Razorpay key. Authentication is a transport concern
 * applied by the adapter at the header level; the model is shown the conversation and the tool
 * catalogue and nothing else. {@code AgentSecurityTest} asserts this rather than trusting it.
 *
 * @param tools tool definitions in the provider-neutral JSON-Schema shape
 *              {@code name}/{@code description}/{@code input_schema}, ordered by name
 */
public record LlmRequest(
        String systemPrompt,
        List<LlmMessage> messages,
        List<Map<String, Object>> tools,
        String model,
        int maxTokens,
        double temperature) {

    public LlmRequest {
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /** The most recent user turn, which is what a scripted client keys its scenario on. */
    public String lastUserText() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            LlmMessage message = messages.get(i);
            if (message.role() == LlmMessage.Role.USER && message.text() != null) {
                return message.text();
            }
        }
        return "";
    }

    /**
     * How many assistant turns have happened <b>since the customer last said something</b> — the
     * step index within the current turn, not within the whole conversation.
     *
     * <p>The distinction matters and was got wrong first time. Counting every assistant message
     * in the transcript means a scripted one-shot scenario fires only on a conversation's very
     * first turn and never again, because by turn two the history already contains assistant
     * messages. Counting from the last user message gives what the agent loop actually iterates
     * over: zero on the first model call of this turn, one on the next.
     *
     * <p>Tool results carry {@link LlmMessage.Role#TOOL} within a turn, so they do not reset the
     * count; only a genuine user turn does.
     */
    public int assistantTurnCount() {
        int lastUser = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).role() == LlmMessage.Role.USER) {
                lastUser = i;
            }
        }
        return (int) messages.subList(lastUser + 1, messages.size()).stream()
                .filter(m -> m.role() == LlmMessage.Role.ASSISTANT)
                .count();
    }

    /** Replaces the message list, keeping every other parameter. Used to append a turn. */
    public LlmRequest withMessages(List<LlmMessage> replacement) {
        return new LlmRequest(systemPrompt, replacement, tools, model, maxTokens, temperature);
    }
}
