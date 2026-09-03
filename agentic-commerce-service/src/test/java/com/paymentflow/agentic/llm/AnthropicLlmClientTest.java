package com.paymentflow.agentic.llm;

import com.paymentflow.agentic.config.AgenticProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

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
 * The production adapter against a stubbed provider.
 *
 * <p>The malformed-output group is the one that matters. Each case is a response this adapter
 * could plausibly receive and could plausibly have tried to salvage — and every one of them
 * must instead be refused outright, because salvaging a tool call means assembling a financial
 * argument list out of something the provider did not actually send.
 */
class AnthropicLlmClientTest {

    private static final String BASE_URI = "http://llm.test";
    private static final String API_KEY = "sk-ant-fixturekeynotreal";

    private MockRestServiceServer server;
    private AnthropicLlmClient client;

    private void build(String model) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URI);
        this.server = MockRestServiceServer.bindTo(builder).build();
        this.client = new AnthropicLlmClient(builder.build(), properties(API_KEY, model));
    }

    private LlmRequest request() {
        return new LlmRequest("system prompt", List.of(LlmMessage.user("hello")),
                List.of(Map.of("name", "search_products", "description", "d",
                        "input_schema", Map.of("type", "object"))),
                "claude-opus-5", 16000, 0.2);
    }

    @Nested
    @DisplayName("the request")
    class Request {

        @Test
        @DisplayName("carries the credential and the pinned API version as headers")
        void sendsCredentialAndVersion() {
            build("claude-opus-5");
            server.expect(requestTo(BASE_URI + "/v1/messages"))
                    .andExpect(header("x-api-key", API_KEY))
                    .andExpect(header("anthropic-version", "2023-06-01"))
                    .andRespond(withSuccess(textResponse("hi"), MediaType.APPLICATION_JSON));

            client.complete(request());

            server.verify();
        }

        @Test
        @DisplayName("omits temperature on a model that rejects sampling parameters")
        void omitsTemperatureOnCurrentModels() {
            build("claude-opus-5");
            server.expect(requestTo(BASE_URI + "/v1/messages"))
                    .andExpect(jsonPath("$.temperature").doesNotExist())
                    .andExpect(jsonPath("$.model").value("claude-opus-5"))
                    .andExpect(jsonPath("$.system").value("system prompt"))
                    .andRespond(withSuccess(textResponse("hi"), MediaType.APPLICATION_JSON));

            client.complete(request());

            server.verify();
        }

        @Test
        @DisplayName("sends temperature on a model that still accepts it")
        void sendsTemperatureOnOlderModels() {
            build("claude-sonnet-4-5");
            server.expect(requestTo(BASE_URI + "/v1/messages"))
                    .andExpect(jsonPath("$.temperature").value(0.2))
                    .andRespond(withSuccess(textResponse("hi"), MediaType.APPLICATION_JSON));

            client.complete(new LlmRequest("system prompt", List.of(LlmMessage.user("hello")), List.of(),
                    "claude-sonnet-4-5", 16000, 0.2));

            server.verify();
        }

        @Test
        @DisplayName("an unknown model is assumed to reject sampling, because omitting it never fails")
        void unknownModelOmitsTemperature() {
            assertThat(AnthropicLlmClient.acceptsSampling("some-future-model")).isFalse();
            assertThat(AnthropicLlmClient.acceptsSampling(null)).isFalse();
            assertThat(AnthropicLlmClient.acceptsSampling("claude-sonnet-4-5")).isTrue();
        }

        @Test
        @DisplayName("tool results are sent as blocks the provider understands, flagged as errors")
        void toolResultsAreSentAsBlocks() {
            build("claude-opus-5");
            server.expect(requestTo(BASE_URI + "/v1/messages"))
                    .andExpect(jsonPath("$.messages[1].content[0].type").value("tool_result"))
                    .andExpect(jsonPath("$.messages[1].content[0].tool_use_id").value("toolu_1"))
                    .andExpect(jsonPath("$.messages[1].content[0].is_error").value(true))
                    .andRespond(withSuccess(textResponse("ok"), MediaType.APPLICATION_JSON));

            client.complete(new LlmRequest("system", List.of(
                    LlmMessage.user("hello"),
                    LlmMessage.toolResults(List.of(
                            LlmToolResult.failure("toolu_1", "request_refund", "{\"ok\":false}")))),
                    List.of(), "claude-opus-5", 16000, 0.2));

            server.verify();
        }
    }

    @Nested
    @DisplayName("the response")
    class Response {

        @Test
        @DisplayName("a tool_use block becomes a structured tool call")
        void parsesToolUse() {
            build("claude-opus-5");
            server.expect(requestTo(BASE_URI + "/v1/messages"))
                    .andRespond(withSuccess("""
                            {"model":"claude-opus-5","stop_reason":"tool_use","content":[
                              {"type":"text","text":"Looking that up."},
                              {"type":"tool_use","id":"toolu_1","name":"search_products",
                               "input":{"query":"tea"}}]}""", MediaType.APPLICATION_JSON));

            LlmResponse response = client.complete(request());

            assertThat(response.stopReason()).isEqualTo(LlmResponse.StopReason.TOOL_USE);
            assertThat(response.requestsTools()).isTrue();
            assertThat(response.toolCalls()).singleElement().satisfies(call -> {
                assertThat(call.id()).isEqualTo("toolu_1");
                assertThat(call.name()).isEqualTo("search_products");
                assertThat(call.arguments()).containsEntry("query", "tea");
            });
            assertThat(response.text()).isEqualTo("Looking that up.");
        }

        @Test
        @DisplayName("an unfamiliar block type is ignored rather than treated as a failure")
        void ignoresUnknownBlockTypes() {
            build("claude-opus-5");
            server.expect(requestTo(BASE_URI + "/v1/messages"))
                    .andRespond(withSuccess("""
                            {"model":"claude-opus-5","stop_reason":"end_turn","content":[
                              {"type":"thinking","thinking":""},
                              {"type":"text","text":"Hello."}]}""", MediaType.APPLICATION_JSON));

            assertThat(client.complete(request()).text()).isEqualTo("Hello.");
        }

        @Test
        @DisplayName("an unrecognised stop reason ends the turn rather than being guessed at")
        void unknownStopReasonEndsTheTurn() {
            build("claude-opus-5");
            server.expect(requestTo(BASE_URI + "/v1/messages"))
                    .andRespond(withSuccess("""
                            {"model":"claude-opus-5","stop_reason":"refusal","content":[]}""",
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
        @DisplayName("a body with no content array")
        void noContentArray() {
            expectMalformed("{\"model\":\"claude-opus-5\",\"stop_reason\":\"end_turn\"}");
        }

        @Test
        @DisplayName("a tool_use block with no name")
        void toolUseWithoutName() {
            expectMalformed("""
                    {"stop_reason":"tool_use","content":[{"type":"tool_use","id":"toolu_1",
                     "input":{"amountMinor":999999}}]}""");
        }

        @Test
        @DisplayName("a tool_use block with no id")
        void toolUseWithoutId() {
            expectMalformed("""
                    {"stop_reason":"tool_use","content":[{"type":"tool_use","name":"request_refund",
                     "input":{}}]}""");
        }

        @Test
        @DisplayName("a tool_use block whose arguments are not an object")
        void toolUseWithScalarInput() {
            expectMalformed("""
                    {"stop_reason":"tool_use","content":[{"type":"tool_use","id":"toolu_1",
                     "name":"request_refund","input":"refund everything"}]}""");
        }

        @Test
        @DisplayName("a stop reason of tool_use with no tool_use block at all")
        void toolUseWithNoBlocks() {
            expectMalformed("""
                    {"stop_reason":"tool_use","content":[{"type":"text","text":"I will refund it."}]}""");
        }

        private void expectMalformed(String body) {
            build("claude-opus-5");
            server.expect(requestTo(BASE_URI + "/v1/messages"))
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
            AnthropicLlmClient unconfigured =
                    new AnthropicLlmClient(builder.build(), properties("", "claude-opus-5"));

            assertThat(unconfigured.isAvailable()).isFalse();
            assertThatThrownBy(() -> unconfigured.complete(request()))
                    .isInstanceOf(LlmUnavailableException.class);
            strictServer.verify();
        }

        @Test
        @DisplayName("a provider error never echoes the provider's own message back")
        void providerErrorTextIsNotRelayed() {
            build("claude-opus-5");
            server.expect(requestTo(BASE_URI + "/v1/messages"))
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
                {"model":"claude-opus-5","stop_reason":"end_turn",
                 "content":[{"type":"text","text":"%s"}]}""".formatted(text);
    }

    private static AgenticProperties properties(String apiKey, String model) {
        return new AgenticProperties(
                new AgenticProperties.Platform("http://gateway.test", "sk_test_x", 2000, 10000),
                new AgenticProperties.Policy("2026-08-20.1", "INR", 5_000_000L, 10_000_000L, 100_000L,
                        2_000_000L, 5_000_000L, 60, 30),
                new AgenticProperties.Checkout(30, 20),
                new AgenticProperties.Llm("anthropic", BASE_URI, apiKey, model, 16000, 0.2, 30000, 8,
                        120000, "", ""),
                new AgenticProperties.Razorpay(false, "https://example.invalid", "", "", 2000, 8000,
                        "decline"),
                new AgenticProperties.Demo("", false));
    }
}
