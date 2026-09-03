package com.paymentflow.agentic.llm;

import com.paymentflow.agentic.config.AgenticProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The provider-selection contract of {@link LlmClientConfig}: which {@link LlmClient} the runtime
 * is handed for a given configuration, decided from configuration alone.
 *
 * <p>This is the seam Project 3's "OpenAI is the default provider" rests on — and the one the
 * scripted fallback rests on, which is what lets CI drive the whole pipeline with no credential.
 * The adapters themselves are covered by {@link OpenAiLlmClientTest} / {@link AnthropicLlmClientTest};
 * this only asserts the choice.
 */
class LlmClientConfigTest {

    private final LlmClientConfig config = new LlmClientConfig();
    private final RestClient restClient = RestClient.builder().baseUrl("http://llm.invalid").build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScriptedLlmClient scripted = new ScriptedLlmClient(objectMapper);

    private LlmClient select(String provider, String anthropicKey, String openAiKey, String openAiModel) {
        AgenticProperties props = new AgenticProperties(
                new AgenticProperties.Platform("http://gateway.test", "sk_test_x", 2000, 10000),
                new AgenticProperties.Policy("2026-08-20.1", "INR", 5_000_000L, 10_000_000L, 100_000L,
                        2_000_000L, 5_000_000L, 60, 30),
                new AgenticProperties.Checkout(30, 20),
                new AgenticProperties.Llm(provider, "", anthropicKey, "claude-opus-5", 16000, 0.2, 30000, 8,
                        120000, openAiKey, openAiModel),
                new AgenticProperties.Razorpay(false, "https://example.invalid", "", "", 2000, 8000, "decline"),
                new AgenticProperties.Demo("", false));
        return config.llmClient(props, restClient, objectMapper, scripted);
    }

    @Nested
    @DisplayName("OpenAI")
    class OpenAi {

        @Test
        @DisplayName("provider=openai with a key selects the OpenAI adapter")
        void openAiWhenConfigured() {
            LlmClient client = select("openai", "", "sk-openai-key", "gpt-4.1");
            assertThat(client).isInstanceOf(OpenAiLlmClient.class);
            assertThat(client.providerName()).isEqualTo("openai");
        }

        @Test
        @DisplayName("provider is matched case-insensitively")
        void caseInsensitive() {
            assertThat(select("OpenAI", "", "sk-openai-key", "gpt-4.1")).isInstanceOf(OpenAiLlmClient.class);
        }

        @Test
        @DisplayName("provider=openai with no OpenAI key falls back to the scripted client")
        void openAiWithoutKeyFallsBack() {
            assertThat(select("openai", "anthropic-key-present", "", "")).isSameAs(scripted);
        }
    }

    @Nested
    @DisplayName("Anthropic")
    class Anthropic {

        @Test
        @DisplayName("provider=anthropic with a key selects the Anthropic adapter")
        void anthropicWhenConfigured() {
            LlmClient client = select("anthropic", "sk-ant-key", "", "");
            assertThat(client).isInstanceOf(AnthropicLlmClient.class);
        }

        @Test
        @DisplayName("the OpenAI key does not satisfy the Anthropic provider")
        void openAiKeyDoesNotConfigureAnthropic() {
            assertThat(select("anthropic", "", "sk-openai-key", "gpt-4.1")).isSameAs(scripted);
        }
    }

    @Nested
    @DisplayName("fallback")
    class Fallback {

        @Test
        @DisplayName("no credential for the selected provider selects the scripted client")
        void noCredentialSelectsScripted() {
            assertThat(select("anthropic", "", "", "")).isSameAs(scripted);
        }

        @Test
        @DisplayName("an unknown provider name selects the scripted client rather than guessing")
        void unknownProviderSelectsScripted() {
            assertThat(select("gemini", "some-key", "some-key", "some-model")).isSameAs(scripted);
        }
    }
}
