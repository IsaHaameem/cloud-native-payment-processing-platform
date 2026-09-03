package com.paymentflow.agentic.llm;

import com.paymentflow.agentic.config.AgenticProperties;
import com.paymentflow.agentic.config.RestClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The production {@link LlmClient}: a thin HTTP adapter over the Anthropic Messages API.
 *
 * <h2>Why an adapter and not the official SDK</h2>
 *
 * <p>Three reasons, in order of weight. This module's {@code build.gradle.kts} already states
 * that {@code RestClient} is the HTTP client for all three outbound integrations and that no
 * new HTTP dependency was introduced for any of them — the {@code llmRestClient} bean has been
 * sitting in {@code RestClientConfig} since the module was created, pointed at this API.
 * Second, Phase 11 asks for a small replaceable adapter rather than a framework. Third, the
 * surface actually used here is one endpoint and five fields; an SDK would be carried, and
 * version-managed through {@code platform-bom}, to save about forty lines.
 *
 * <p>The trade is real and worth naming: no typed models, no built-in retries, and every wire
 * change has to be handled here. That is acceptable precisely because the surface is small and
 * because {@link LlmClient} makes replacing this class a contained change.
 *
 * <h2>Two fields deliberately not sent</h2>
 *
 * <ul>
 *   <li><b>{@code temperature}</b>, unless the configured model is known to accept it. Current
 *       models removed sampling parameters and <b>reject the field with a 400</b>. An unknown
 *       model is treated as not accepting it, because omitting the field never fails a request
 *       and sending it can — the fail-safe direction is the one that keeps working when this
 *       list goes stale.</li>
 *   <li><b>{@code output_config.effort}</b>, at all. It is unsupported on some older models the
 *       {@code model} property could legitimately name, and a request parameter that 400s
 *       depending on configuration is worse than a default nobody tuned.</li>
 * </ul>
 *
 * <h2>The credential</h2>
 *
 * <p>The key is set as a header at the moment of the call and appears nowhere else — not on
 * {@link LlmRequest}, not in a log line, not in an exception message. Failure text from the
 * provider is not echoed back at all: it is written by someone else and this service is not in
 * a position to promise it carries nothing sensitive.
 */
public class AnthropicLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicLlmClient.class);

    private static final String MESSAGES_PATH = "/v1/messages";

    /** The dated API version this adapter is written against. Pinned, for the same reason the platform hop is. */
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private static final String HEADER_API_KEY = "x-api-key";
    private static final String HEADER_VERSION = "anthropic-version";

    /**
     * Models still accepting {@code temperature}. Everything newer removed sampling parameters
     * and returns a 400 for them; anything not listed here is assumed to have removed them too.
     */
    private static final Set<String> MODELS_ACCEPTING_SAMPLING = Set.of(
            "claude-sonnet-4-5", "claude-haiku-4-5", "claude-opus-4-5",
            "claude-opus-4-6", "claude-sonnet-4-6");

    private final RestClient restClient;
    private final AgenticProperties properties;

    public AnthropicLlmClient(@Qualifier(RestClientConfig.LLM_CLIENT) RestClient restClient,
                              AgenticProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public String providerName() {
        return "anthropic";
    }

    @Override
    public boolean isAvailable() {
        return properties.llm().isConfigured();
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        if (!isAvailable()) {
            throw new LlmUnavailableException(
                    "No language-model credential is configured. Set ANTHROPIC_API_KEY, or leave it unset "
                            + "to run the deterministic scripted client instead.");
        }
        try {
            Map<String, Object> body = restClient.post()
                    .uri(MESSAGES_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        headers.set(HEADER_API_KEY, properties.llm().activeApiKey());
                        headers.set(HEADER_VERSION, ANTHROPIC_VERSION);
                        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                    })
                    .body(toRequestBody(request))
                    .exchange((httpRequest, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.isError()) {
                            // The provider's own message is deliberately not read or relayed:
                            // it is authored elsewhere and could echo back any part of the
                            // request, including a header this service is careful never to log.
                            throw new LlmUnavailableException(
                                    "The language model returned HTTP %d.".formatted(status.value()));
                        }
                        return response.bodyTo(MAP_TYPE);
                    }, false);

            return parse(body);

        } catch (ResourceAccessException e) {
            throw new LlmUnavailableException(
                    "The language model could not be reached: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static final Class<Map<String, Object>> MAP_TYPE = (Class<Map<String, Object>>) (Class<?>) Map.class;

    // ── Request ─────────────────────────────────────────────────────────────────────────

    private Map<String, Object> toRequestBody(LlmRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("max_tokens", request.maxTokens());
        body.put("system", request.systemPrompt());
        if (!request.tools().isEmpty()) {
            body.put("tools", request.tools());
        }
        if (acceptsSampling(request.model())) {
            body.put("temperature", request.temperature());
        }
        body.put("messages", toWireMessages(request.messages()));
        return body;
    }

    static boolean acceptsSampling(String model) {
        return model != null && MODELS_ACCEPTING_SAMPLING.contains(model.toLowerCase(Locale.ROOT));
    }

    /**
     * Maps the neutral message list onto the provider's wire shape.
     *
     * <p>The one non-obvious mapping is {@link LlmMessage.Role#TOOL}: this provider carries tool
     * results as blocks inside a <em>user</em> message rather than a role of their own. Keeping
     * that knowledge here is exactly what {@link LlmClient} exists for.
     */
    private static List<Map<String, Object>> toWireMessages(List<LlmMessage> messages) {
        List<Map<String, Object>> wire = new ArrayList<>(messages.size());
        for (LlmMessage message : messages) {
            switch (message.role()) {
                case USER -> wire.add(Map.of("role", "user", "content", message.text()));
                case ASSISTANT -> wire.add(assistantMessage(message));
                case TOOL -> wire.add(toolResultMessage(message));
            }
        }
        return wire;
    }

    private static Map<String, Object> assistantMessage(LlmMessage message) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        if (message.text() != null && !message.text().isBlank()) {
            blocks.add(Map.of("type", "text", "text", message.text()));
        }
        for (LlmToolCall call : message.toolCalls()) {
            blocks.add(Map.of("type", "tool_use", "id", call.id(), "name", call.name(),
                    "input", call.arguments()));
        }
        // An assistant turn with neither text nor tool calls is not representable on the wire;
        // a single space keeps the transcript well-formed rather than failing the whole turn.
        if (blocks.isEmpty()) {
            blocks.add(Map.of("type", "text", "text", " "));
        }
        return Map.of("role", "assistant", "content", blocks);
    }

    private static Map<String, Object> toolResultMessage(LlmMessage message) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (LlmToolResult result : message.toolResults()) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "tool_result");
            block.put("tool_use_id", result.toolCallId());
            block.put("content", result.content());
            if (result.error()) {
                // Surfaced as an error rather than as ordinary content, so a refusal or a
                // decline has to be accounted for in the reply instead of being narrated past.
                block.put("is_error", true);
            }
            blocks.add(block);
        }
        return Map.of("role", "user", "content", blocks);
    }

    // ── Response ────────────────────────────────────────────────────────────────────────

    /**
     * Reads the response, or refuses it.
     *
     * <p>Every failure below throws {@link MalformedLlmOutputException}. None of them attempts
     * a partial read: a {@code tool_use} block missing its name is not turned into a guess, and
     * a response whose {@code content} is not a list is not scanned for something usable.
     */
    @SuppressWarnings("unchecked")
    private static LlmResponse parse(Map<String, Object> body) {
        if (body == null) {
            throw new MalformedLlmOutputException("The language model returned an empty body.");
        }
        String model = body.get("model") instanceof String value ? value : null;
        Object rawContent = body.get("content");
        if (!(rawContent instanceof List<?> content)) {
            throw new MalformedLlmOutputException(
                    "The language model response had no content array.");
        }

        StringBuilder text = new StringBuilder();
        List<LlmToolCall> toolCalls = new ArrayList<>();

        for (Object rawBlock : content) {
            if (!(rawBlock instanceof Map<?, ?> block)) {
                throw new MalformedLlmOutputException(
                        "The language model returned a content block that was not an object.");
            }
            String type = block.get("type") instanceof String value ? value : "";
            switch (type) {
                case "text" -> {
                    if (block.get("text") instanceof String value) {
                        text.append(value);
                    }
                }
                case "tool_use" -> toolCalls.add(readToolUse(block));
                default -> {
                    // Thinking blocks and anything else this adapter has no use for. Ignored
                    // rather than rejected: an unfamiliar block type is a provider addition,
                    // not a malformed response, and failing on one would break this service on
                    // a change that broke nothing else.
                }
            }
        }

        LlmResponse.StopReason stopReason = mapStopReason(body.get("stop_reason"));
        if (stopReason == LlmResponse.StopReason.TOOL_USE && toolCalls.isEmpty()) {
            throw new MalformedLlmOutputException(
                    "The language model reported tool use but returned no tool_use block.");
        }
        return new LlmResponse(text.isEmpty() ? null : text.toString(), toolCalls, stopReason, model);
    }

    @SuppressWarnings("unchecked")
    private static LlmToolCall readToolUse(Map<?, ?> block) {
        if (!(block.get("id") instanceof String id) || id.isBlank()) {
            throw new MalformedLlmOutputException("A tool_use block carried no id.");
        }
        if (!(block.get("name") instanceof String name) || name.isBlank()) {
            throw new MalformedLlmOutputException("A tool_use block carried no tool name.");
        }
        Object input = block.get("input");
        if (input != null && !(input instanceof Map<?, ?>)) {
            throw new MalformedLlmOutputException(
                    "A tool_use block for '%s' carried arguments that were not an object.".formatted(name));
        }
        Map<String, Object> arguments = input == null ? Map.of() : (Map<String, Object>) input;
        return new LlmToolCall(id, name, arguments);
    }

    private static LlmResponse.StopReason mapStopReason(Object raw) {
        if (!(raw instanceof String value)) {
            return LlmResponse.StopReason.OTHER;
        }
        return switch (value) {
            case "end_turn", "stop_sequence" -> LlmResponse.StopReason.END_TURN;
            case "tool_use" -> LlmResponse.StopReason.TOOL_USE;
            case "max_tokens" -> LlmResponse.StopReason.MAX_TOKENS;
            default -> {
                // Includes `refusal` and `pause_turn`. Mapped to OTHER, which the runtime ends
                // the turn on without executing anything — the safe reading of a stop reason
                // this adapter was not written for.
                log.info("The language model stopped with an unhandled reason: {}", value);
                yield LlmResponse.StopReason.OTHER;
            }
        };
    }
}
