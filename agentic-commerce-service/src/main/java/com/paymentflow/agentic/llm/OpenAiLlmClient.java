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

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The second production {@link LlmClient}: a thin HTTP adapter over the OpenAI Chat Completions
 * API ({@code POST /v1/chat/completions}).
 *
 * <h2>Why this looks so much like {@link AnthropicLlmClient}</h2>
 *
 * <p>By intent. The two adapters are the <em>only</em> place a provider difference is allowed to
 * live, so everything else about them is deliberately identical: the same {@code RestClient}
 * bean, the same socket timeouts, the same "never retry a mutation" posture (there is no retry
 * here at all — a turn is re-run, not a request), the same refusal to relay the provider's own
 * error text, and the same rule that <b>malformed output is refused, never repaired</b>. A
 * response missing a tool name, or carrying tool arguments that are not valid JSON, ends the
 * turn having executed nothing rather than being salvaged into a financial argument list the
 * model did not actually send.
 *
 * <h2>The three wire differences this class absorbs</h2>
 *
 * <ul>
 *   <li><b>Tool definitions.</b> {@link LlmRequest} carries them in the neutral
 *       {@code name}/{@code description}/{@code input_schema} shape Anthropic uses directly.
 *       OpenAI wants {@code {"type":"function","function":{"name","description","parameters"}}},
 *       so {@code input_schema} is remapped to {@code parameters} here.</li>
 *   <li><b>Tool-call arguments are a JSON string, not an object.</b> OpenAI returns
 *       {@code function.arguments} as a string of JSON; this adapter parses it into a
 *       {@code Map} for {@link LlmToolCall}. A string that is not a JSON object is malformed
 *       output.</li>
 *   <li><b>Tool results are their own role.</b> Anthropic carries a tool result as a block
 *       inside a user message; OpenAI has a {@code role:"tool"} message keyed by
 *       {@code tool_call_id}. OpenAI has no {@code is_error} flag on that message — the failure
 *       is already inside the JSON {@code ToolResult} the content carries, which is what the
 *       model reads — so nothing is lost, but it is worth naming.</li>
 * </ul>
 *
 * <h2>The credential</h2>
 *
 * <p>The key is set as an {@code Authorization: Bearer} header at the moment of the call and
 * appears nowhere else — not on {@link LlmRequest}, not in a log line, not in an exception
 * message. Provider failure text is not read or echoed back: it is authored elsewhere and could
 * repeat any part of the request.
 */
public class OpenAiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiLlmClient.class);

    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    private static final String HEADER_AUTHORIZATION = "Authorization";

    /**
     * Models still accepting a caller-set {@code temperature}. The reasoning models (the
     * {@code o*} and {@code gpt-5} families) reject any non-default value with a 400, so anything
     * not listed here is assumed to reject it too — omitting the field never fails a request and
     * sending it can, so an unknown model fails safe. Mirrors {@code AnthropicLlmClient}.
     */
    private static final Set<String> MODELS_ACCEPTING_SAMPLING = Set.of(
            "gpt-4.1", "gpt-4.1-mini", "gpt-4.1-nano",
            "gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-3.5-turbo");

    private final RestClient restClient;
    private final AgenticProperties properties;
    private final ObjectMapper objectMapper;

    public OpenAiLlmClient(@Qualifier(RestClientConfig.LLM_CLIENT) RestClient restClient,
                           AgenticProperties properties, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerName() {
        return "openai";
    }

    @Override
    public boolean isAvailable() {
        return properties.llm().isConfigured();
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        if (!isAvailable()) {
            throw new LlmUnavailableException(
                    "No language-model credential is configured. Set OPENAI_API_KEY (with "
                            + "AGENTIC_LLM_PROVIDER=openai), or leave it unset to run the deterministic "
                            + "scripted client instead.");
        }
        try {
            Map<String, Object> body = restClient.post()
                    .uri(CHAT_COMPLETIONS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        headers.set(HEADER_AUTHORIZATION, "Bearer " + properties.llm().activeApiKey());
                        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                    })
                    .body(toRequestBody(request))
                    .exchange((httpRequest, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.isError()) {
                            // The provider's own message is deliberately not read or relayed:
                            // it is authored elsewhere and could echo back any part of the
                            // request, including a header this service never logs.
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
        // max_completion_tokens, not the deprecated max_tokens: the reasoning models reject the
        // latter, and the former is accepted by every current chat model.
        body.put("max_completion_tokens", request.maxTokens());
        if (acceptsSampling(request.model())) {
            body.put("temperature", request.temperature());
        }
        if (!request.tools().isEmpty()) {
            body.put("tools", toWireTools(request.tools()));
            body.put("tool_choice", "auto");
        }
        body.put("messages", toWireMessages(request.systemPrompt(), request.messages()));
        return body;
    }

    static boolean acceptsSampling(String model) {
        return model != null && MODELS_ACCEPTING_SAMPLING.contains(model.toLowerCase(Locale.ROOT));
    }

    /**
     * Remaps the neutral tool definitions onto OpenAI's {@code function} envelope. The only
     * change is {@code input_schema} → {@code parameters}; the schema object itself is passed
     * through unaltered.
     */
    private static List<Map<String, Object>> toWireTools(List<Map<String, Object>> tools) {
        List<Map<String, Object>> wire = new ArrayList<>(tools.size());
        for (Map<String, Object> tool : tools) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tool.get("name"));
            if (tool.get("description") != null) {
                function.put("description", tool.get("description"));
            }
            Object schema = tool.get("input_schema");
            function.put("parameters", schema != null ? schema : Map.of("type", "object"));
            wire.add(Map.of("type", "function", "function", function));
        }
        return wire;
    }

    /**
     * Maps the neutral message list onto OpenAI's wire shape.
     *
     * <p>The system prompt is the first message rather than a top-level field, and a
     * {@link LlmMessage.Role#TOOL} turn expands to one {@code role:"tool"} message per result,
     * each keyed by the {@code tool_call_id} it answers — the pairing OpenAI requires after an
     * assistant turn that carried {@code tool_calls}.
     */
    private List<Map<String, Object>> toWireMessages(String systemPrompt, List<LlmMessage> messages) {
        List<Map<String, Object>> wire = new ArrayList<>(messages.size() + 1);
        wire.add(Map.of("role", "system", "content", systemPrompt));
        for (LlmMessage message : messages) {
            switch (message.role()) {
                case USER -> wire.add(Map.of("role", "user", "content", message.text()));
                case ASSISTANT -> wire.add(assistantMessage(message));
                case TOOL -> {
                    for (LlmToolResult result : message.toolResults()) {
                        Map<String, Object> toolMessage = new LinkedHashMap<>();
                        toolMessage.put("role", "tool");
                        toolMessage.put("tool_call_id", result.toolCallId());
                        toolMessage.put("content", result.content() == null ? "" : result.content());
                        wire.add(toolMessage);
                    }
                }
            }
        }
        return wire;
    }

    private Map<String, Object> assistantMessage(LlmMessage message) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("role", "assistant");
        // content is required on an assistant message; null is allowed only alongside tool_calls.
        wire.put("content", message.text() == null || message.text().isBlank() ? null : message.text());
        if (!message.toolCalls().isEmpty()) {
            List<Map<String, Object>> calls = new ArrayList<>(message.toolCalls().size());
            for (LlmToolCall call : message.toolCalls()) {
                calls.add(Map.of(
                        "id", call.id(),
                        "type", "function",
                        "function", Map.of(
                                "name", call.name(),
                                "arguments", writeArguments(call.arguments()))));
            }
            wire.put("tool_calls", calls);
        } else if (wire.get("content") == null) {
            // An assistant turn with neither text nor tool calls is not representable; a single
            // space keeps the transcript well-formed rather than failing the whole turn.
            wire.put("content", " ");
        }
        return wire;
    }

    private String writeArguments(Map<String, Object> arguments) {
        try {
            return objectMapper.writeValueAsString(arguments == null ? Map.of() : arguments);
        } catch (JacksonException e) {
            // The arguments came from our own registry-validated call on a prior turn, so this
            // is not expected; "{}" keeps the transcript valid rather than aborting the turn.
            log.warn("Could not serialise prior tool-call arguments for the transcript; sending an empty object.");
            return "{}";
        }
    }

    // ── Response ────────────────────────────────────────────────────────────────────────

    /**
     * Reads the response, or refuses it. Every failure below throws
     * {@link MalformedLlmOutputException}, and none attempts a partial read.
     */
    private LlmResponse parse(Map<String, Object> body) {
        if (body == null) {
            throw new MalformedLlmOutputException("The language model returned an empty body.");
        }
        String model = body.get("model") instanceof String value ? value : null;

        if (!(body.get("choices") instanceof List<?> choices) || choices.isEmpty()) {
            throw new MalformedLlmOutputException("The language model response had no choices.");
        }
        if (!(choices.getFirst() instanceof Map<?, ?> choice)) {
            throw new MalformedLlmOutputException("The language model returned a choice that was not an object.");
        }
        if (!(choice.get("message") instanceof Map<?, ?> messageBlock)) {
            throw new MalformedLlmOutputException("The language model choice carried no message object.");
        }

        String text = messageBlock.get("content") instanceof String value && !value.isBlank() ? value : null;
        LlmResponse.StopReason stopReason = mapFinishReason(choice.get("finish_reason"));

        List<LlmToolCall> toolCalls = new ArrayList<>();
        if (stopReason == LlmResponse.StopReason.TOOL_USE) {
            if (!(messageBlock.get("tool_calls") instanceof List<?> rawCalls) || rawCalls.isEmpty()) {
                throw new MalformedLlmOutputException(
                        "The language model reported tool use but returned no tool_calls.");
            }
            for (Object rawCall : rawCalls) {
                if (!(rawCall instanceof Map<?, ?> call)) {
                    throw new MalformedLlmOutputException(
                            "The language model returned a tool call that was not an object.");
                }
                toolCalls.add(readToolCall(call));
            }
        }

        return new LlmResponse(text, toolCalls, stopReason, model);
    }

    private LlmToolCall readToolCall(Map<?, ?> call) {
        if (!(call.get("id") instanceof String id) || id.isBlank()) {
            throw new MalformedLlmOutputException("A tool call carried no id.");
        }
        if (!(call.get("function") instanceof Map<?, ?> function)) {
            throw new MalformedLlmOutputException("A tool call carried no function object.");
        }
        if (!(function.get("name") instanceof String name) || name.isBlank()) {
            throw new MalformedLlmOutputException("A tool call carried no tool name.");
        }
        Object rawArguments = function.get("arguments");
        if (rawArguments != null && !(rawArguments instanceof String)) {
            throw new MalformedLlmOutputException(
                    "The tool call for '%s' carried arguments that were not a JSON string.".formatted(name));
        }
        return new LlmToolCall(id, name, parseArguments(name, (String) rawArguments));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(String toolName, String rawArguments) {
        if (rawArguments == null || rawArguments.isBlank()) {
            // OpenAI sends "" or "{}" for a call with no arguments. Both mean the empty object.
            return Map.of();
        }
        Object parsed;
        try {
            parsed = objectMapper.readValue(rawArguments, Object.class);
        } catch (JacksonException e) {
            throw new MalformedLlmOutputException(
                    "The tool call for '%s' carried arguments that were not valid JSON.".formatted(toolName));
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new MalformedLlmOutputException(
                    "The tool call for '%s' carried arguments that were not a JSON object.".formatted(toolName));
        }
        return (Map<String, Object>) map;
    }

    private static LlmResponse.StopReason mapFinishReason(Object raw) {
        if (!(raw instanceof String value)) {
            return LlmResponse.StopReason.OTHER;
        }
        return switch (value) {
            case "stop" -> LlmResponse.StopReason.END_TURN;
            case "tool_calls" -> LlmResponse.StopReason.TOOL_USE;
            case "length" -> LlmResponse.StopReason.MAX_TOKENS;
            default -> {
                // Includes `content_filter` and the legacy `function_call`. Mapped to OTHER,
                // which the runtime ends the turn on without executing anything.
                log.info("The language model stopped with an unhandled finish_reason: {}", value);
                yield LlmResponse.StopReason.OTHER;
            }
        };
    }
}
