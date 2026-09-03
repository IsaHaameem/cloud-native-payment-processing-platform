package com.paymentflow.agentic.llm;

import com.paymentflow.agentic.config.AgenticProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The OpenAI adapter against a stubbed provider.
 *
 * <p>Mirrors {@link AnthropicLlmClientTest} case for case, because the two adapters are meant to
 * be interchangeable and the guarantees that matter — no credential leak, and malformed output
 * refused rather than repaired — are the same guarantees. The OpenAI-specific rows are the ones
 * about the {@code function} tool envelope and the tool-call {@code arguments} being a JSON
 * <em>string</em> that this adapter parses.
 */
class OpenAiLlmClientTest {

    private static final String BASE_URI = "http://llm.test";
    private static final String API_KEY = "sk-openai-fixturekeynotreal";

    private MockRestServiceServer server;
    private OpenAiLlmClient client;

    private void build(String model) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URI);
        this.server = MockRestServiceServer.bindTo(builder).build();
        this.client = new OpenAiLlmClient(builder.build(), properties(API_KEY, model), new ObjectMapper());
    }

    private LlmRequest request() {
        return new LlmRequest("system prompt", List.of(LlmMessage.user("hello")),
                List.of(Map.of("name", "search_products", "description", "d",
                        "input_schema", Map.of("type", "object"))),
                "gpt-5", 16000, 0.2);
    }

    @Nested
    @DisplayName("the request")
    class Request {

        @Test
        @DisplayName("carries the credential as a bearer token and hits chat/completions")
        void sendsCredential() {
            build("gpt-5");
            server.expect(requestTo(BASE_URI + "/v1/chat/completions"))
                    .andExpect(header("Authorization", "Bearer " + API_KEY))
                    .andRespond(withSuccess(textResponse("hi"), MediaType.APPLICATION_JSON));

            client.complete(request());

            server.verify();
        }

        @Test
        @DisplayName("the system prompt is the first message and max_completion_tokens is sent")
        void systemPromptAndTokenCeiling() {
            build("gpt-5");
            server.expect(requestTo(BASE_URI + "/v1/chat/completions"))
                    .andExpect(jsonPath("$.messages[0].role").value("system"))
                    .andExpect(jsonPath("$.messages[0].content").value("system prompt"))
                    .andExpect(jsonPath("$.messages[1].role").value("user"))
                    .andExpect(jsonPath("$.max_completion_tokens").value(16000))
                    .andExpect(jsonPath("$.model").value("gpt-5"))
                    .andRespond(withSuccess(textResponse("hi"), MediaType.APPLICATION_JSON));

            client.complete(request());

            server.verify();
        }

        @Test
        @DisplayName("omits temperature on a reasoning model that rejects sampling parameters")
        void omitsTemperatureOnReasoningModels() {
            build("gpt-5");
            server.expect(requestTo(BASE_URI + "/v1/chat/completions"))
                    .andExpect(jsonPath("$.temperature").doesNotExist())
                    .andRespond(withSuccess(textResponse("hi"), MediaType.APPLICATION_JSON));

            client.complete(request());

            server.verify();
        }

        @Test
        @DisplayName("sends temperature on a model that still accepts it")
        void sendsTemperatureOnChatModels() {
            build("gpt-4.1");
            server.expect(requestTo(BASE_URI + "/v1/chat/completions"))
                    .andExpect(jsonPath("$.temperature").value(0.2))
                    .andRespond(withSuccess(textResponse("hi"), MediaType.APPLICATION_JSON));

            client.complete(new LlmRequest("system prompt", List.of(LlmMessage.user("hello")), List.of(),
                    "gpt-4.1", 16000, 0.2));

            server.verify();
        }

        @Test
        @DisplayName("an unknown model is assumed to reject sampling, because omitting it never fails")
        void unknownModelOmitsTemperature() {
            assertThat(OpenAiLlmClient.acceptsSampling("some-future-model")).isFalse();
            assertThat(OpenAiLlmClient.acceptsSampling(null)).isFalse();
            assertThat(OpenAiLlmClient.acceptsSampling("gpt-4.1")).isTrue();
        }

        @Test
        @DisplayName("tools are remapped into OpenAI's function envelope with parameters, not input_schema")
        void toolsUseFunctionEnvelope() {
            build("gpt-5");
            server.expect(requestTo(BASE_URI + "/v1/chat/completions"))
                    .andExpect(jsonPath("$.tools[0].type").value("function"))
                    .andExpect(jsonPath("$.tools[0].function.name").value("search_products"))
                    .andExpect(jsonPath("$.tools[0].function.parameters.type").value("object"))
                    .andExpect(jsonPath("$.tools[0].function.input_schema").doesNotExist())
                    .andExpect(jsonPath("$.tool_choice").value("auto"))
                    .andRespond(withSuccess(textResponse("hi"), MediaType.APPLICATION_JSON));

            client.complete(request());

            server.verify();
        }

        @Test
        @DisplayName("a tool result becomes its own role:tool message keyed by tool_call_id")
        void toolResultsAreOwnRole() {
            build("gpt-5");
            server.expect(requestTo(BASE_URI + "/v1/chat/completions"))
                    .andExpect(jsonPath("$.messages[2].role").value("tool"))
                    .andExpect(jsonPath("$.messages[2].tool_call_id").value("call_1"))
                    .andExpect(jsonPath("$.messages[2].content").value("{\"ok\":false}"))
                    .andRespond(withSuccess(textResponse("ok"), MediaType.APPLICATION_JSON));

            client.complete(new LlmRequest("system", List.of(
                    LlmMessage.user("hello"),
                    LlmMessage.toolResults(List.of(
                            LlmToolResult.failure("call_1", "request_refund", "{\"ok\":false}")))),
                    List.of(), "gpt-5", 16000, 0.2));

            server.verify();
        }

        @Test
        @DisplayName("a prior assistant tool call is serialised with arguments as a JSON string")
        void assistantToolCallArgumentsAreAString() {
            build("gpt-5");
            server.expect(requestTo(BASE_URI + "/v1/chat/completions"))
                    .andExpect(jsonPath("$.messages[2].role").value("assistant"))
                    .andExpect(jsonPath("$.messages[2].tool_calls[0].id").value("call_9"))
                    .andExpect(jsonPath("$.messages[2].tool_calls[0].type").value("function"))
                    .andExpect(jsonPath("$.messages[2].tool_calls[0].function.name").value("search_products"))
                    .andExpect(jsonPath("$.messages[2].tool_calls[0].function.arguments").value("{\"query\":\"tea\"}"))
                    .andRespond(withSuccess(textResponse("ok"), MediaType.APPLICATION_JSON));

            client.complete(new LlmRequest("system", List.of(
                    LlmMessage.user("hello"),
                    LlmMessage.assistant(null, List.of(
                            new LlmToolCall("call_9", "search_products", Map.of("query", "tea"))))),
                    List.of(), "gpt-5", 16000, 0.2));

            server.verify();
        }
    }

    @Nested
    @DisplayName("the response")
    class Response {

        @Test
        @DisplayName("a tool_calls finish reason with a function call becomes a structured tool call")
        void parsesToolCall() {
            build("gpt-5");
            server.expect(requestTo(BASE_URI + "/v1/chat/completions"))
                    .andRespond(withSuccess("""
                            {"model":"gpt-5","choices":[{"finish_reason":"tool_calls","message":{
                              "role":"assistant","content":"Looking that up.",
                              "tool_calls":[{"id":"call_1","type":"function","function":{
                                "name":"search_products","arguments":"{\\"query\\":\\"tea\\"}"}}]}}]}""",
                            MediaType.APPLICATION_JSON));

            LlmResponse response = client.complete(request());

            assertThat(response.stopReason()).isEqualTo(LlmResponse.StopReason.TOOL_USE);
            assertThat(response.requestsTools()).isTrue();
            assertThat(response.toolCalls()).singleElement().satisfies(call -> {
                assertThat(call.id()).isEqualTo("call_1");
                assertThat(call.name()).isEqualTo("search_products");
                assertThat(call.arguments()).containsEntry("query", "tea");
            });
            assertThat(response.text()).isEqualTo("Looking that up.");
        }

        @Test
        @DisplayName("a call with empty-string arguments is read as an empty object")
        void emptyArgumentsString() {
            build("gpt-5");
            server.expect(requestTo(BASE_URI + "/v1/chat/completions"))
                    .andRespond(withSuccess("""
                            {"model":"gpt-5","choices":[{"finish_reason":"tool_calls","message":{
                              "role":"assistant","content":null,
                              "tool_calls":[{"id":"call_1","type":"function","function":{
                                "name":"search_products","arguments":""}}]}}]}""",
                            MediaType.APPLICATION_JSON));

            LlmResponse response = client.complete(request());

            assertThat(response.toolCalls()).singleElement().satisfies(call ->
                    assertThat(call.arguments()).isEmpty());
        }

        @Test
        @DisplayName("finish_reason stop ends the turn with text")
        void plainTextReply() {
            build("gpt-5");
            server.expect(requestTo(BASE_URI + "/v1/chat/completions"))
                    .andRespond(withSuccess(textResponse("Hello."), MediaType.APPLICATION_JSON));

            LlmResponse response = client.complete(request());

            assertThat(response.stopReason()).isEqualTo(LlmResponse.StopReason.END_TURN);
            assertThat(response.requestsTools()).isFalse();
            assertThat(response.text()).isEqualTo("Hello.");
        }

        @Test
        @DisplayName("finish_reason length maps to MAX_TOKENS and executes nothing")
        void lengthMapsToMaxTokens() {
            build("gpt-5");
            server.expect(requestTo(BASE_URI + "/v1/chat/completions"))
                    .andRespond(withSuccess("""
                            {"model":"gpt-5","choices":[{"finish_reason":"length","message":{
                              "role":"assistant","content":"half a sen"}}]}""",
                            MediaType.APPLICATION_JSON));

            LlmResponse response = client.complete(request());

            assertThat(response.stopReason()).isEqualTo(LlmResponse.StopReason.MAX_TOKENS);
            assertThat(response.requestsTools()).isFalse();
        }

        @Test
        @DisplayName("an unrecognised finish reason ends the turn rather than being guessed at")
        void unknownFinishReasonEndsTheTurn() {
            build("gpt-5");
            server.expect(requestTo(BASE_URI + "/v1/chat/completions"))
                    .andRespond(withSuccess("""
                            {"model":"gpt-5","choices":[{"finish_reason":"content_filter","message":{
                              "role":"assistant","content":null}}]}""",
                            MediaType.APPLICATION_JSON));

            LlmResponse response = client.complete(request());

            assertThat(response.stopReason()).isEqualTo(LlmResponse.StopReason.OTHER);
            assertThat(response.requestsTools()).isFalse();
        }
    }

    @Nested
    @DisplayName("malformed output is refused, never repaired")
    class Malformed {

        @Test
        @DisplayName("a body with no choices")
        void noChoices() {
            expectMalformed("{\"model\":\"gpt-5\"}");
        }

        @Test
        @DisplayName("an empty choices array")
        void emptyChoices() {
            expectMalformed("{\"model\":\"gpt-5\",\"choices\":[]}");
        }

        @Test
        @DisplayName("a choice with no message object")
        void noMessageObject() {
            expectMalformed("{\"choices\":[{\"finish_reason\":\"stop\"}]}");
        }

        @Test
        @DisplayName("a tool call with no id")
        void toolCallWithoutId() {
            expectMalformed("""
                    {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
                     "tool_calls":[{"type":"function","function":{"name":"request_refund","arguments":"{}"}}]}}]}""");
        }

        @Test
        @DisplayName("a tool call whose function has no name")
        void toolCallWithoutName() {
            expectMalformed("""
                    {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
                     "tool_calls":[{"id":"call_1","type":"function","function":{"arguments":"{\\"amountMinor\\":999999}"}}]}}]}""");
        }

        @Test
        @DisplayName("a tool call whose arguments are an object, not the JSON string OpenAI sends")
        void toolCallArgumentsNotAString() {
            expectMalformed("""
                    {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
                     "tool_calls":[{"id":"call_1","type":"function","function":{"name":"request_refund",
                      "arguments":{"amountMinor":999999}}}]}}]}""");
        }

        @Test
        @DisplayName("a tool call whose arguments string is not valid JSON")
        void toolCallArgumentsNotValidJson() {
            expectMalformed("""
                    {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
                     "tool_calls":[{"id":"call_1","type":"function","function":{"name":"request_refund",
                      "arguments":"refund everything"}}]}}]}""");
        }

        @Test
        @DisplayName("a tool call whose arguments string is JSON but not an object")
        void toolCallArgumentsNotAnObject() {
            expectMalformed("""
                    {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
                     "tool_calls":[{"id":"call_1","type":"function","function":{"name":"request_refund",
                      "arguments":"[1,2,3]"}}]}}]}""");
        }

        @Test
        @DisplayName("a finish reason of tool_calls with no tool_calls array at all")
        void toolCallsFinishWithNoCalls() {
            expectMalformed("""
                    {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant",
                     "content":"I will refund it."}}]}""");
        }

        private void expectMalformed(String body) {
            build("gpt-5");
            server.expect(requestTo(BASE_URI + "/v1/chat/completions"))
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> client.complete(request()))
                    .isInstanceOf(MalformedLlmOutputException.class);
        }
    }

    @Nested
    @DisplayName("availability and secrecy")
    class Availability {

        @Test
        @DisplayName("an unconfigured client refuses before sending anything")
        void unconfiguredRefuses() {
            RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URI);
            MockRestServiceServer strictServer = MockRestServiceServer.bindTo(builder).build();
            OpenAiLlmClient unconfigured =
                    new OpenAiLlmClient(builder.build(), properties("", "gpt-5"), new ObjectMapper());

            assertThat(unconfigured.isAvailable()).isFalse();
            assertThatThrownBy(() -> unconfigured.complete(request()))
                    .isInstanceOf(LlmUnavailableException.class);
            strictServer.verify();
        }

        @Test
        @DisplayName("a provider error never echoes the provider's own message back")
        void providerErrorTextIsNotRelayed() {
            build("gpt-5");
            server.expect(requestTo(BASE_URI + "/v1/chat/completions"))
                    .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"error\":{\"message\":\"invalid key " + API_KEY + "\"}}"));

            assertThatThrownBy(() -> client.complete(request()))
                    .isInstanceOf(LlmUnavailableException.class)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain(API_KEY));
        }
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────────────

    private static String textResponse(String text) {
        return """
                {"model":"gpt-5","choices":[{"finish_reason":"stop","message":{
                 "role":"assistant","content":"%s"}}]}""".formatted(text);
    }

    private static AgenticProperties properties(String openAiApiKey, String model) {
        return new AgenticProperties(
                new AgenticProperties.Platform("http://gateway.test", "sk_test_x", 2000, 10000),
                new AgenticProperties.Policy("2026-08-20.1", "INR", 5_000_000L, 10_000_000L, 100_000L,
                        2_000_000L, 5_000_000L, 60, 30),
                new AgenticProperties.Checkout(30, 20),
                new AgenticProperties.Llm("openai", BASE_URI, "", "claude-opus-5", 16000, 0.2, 30000, 8,
                        120000, openAiApiKey, model),
                new AgenticProperties.Razorpay(false, "https://example.invalid", "", "", 2000, 8000,
                        "decline"),
                new AgenticProperties.Demo("", false));
    }
}
