package com.paymentflow.agentic.runtime;

import com.paymentflow.agentic.action.Redactor;
import com.paymentflow.agentic.config.AgenticProperties;
import com.paymentflow.agentic.conversation.ConversationMessage;
import com.paymentflow.agentic.llm.LlmClient;
import com.paymentflow.agentic.llm.LlmRequest;
import com.paymentflow.agentic.llm.LlmResponse;
import com.paymentflow.agentic.platform.PaymentFlowClientException;
import com.paymentflow.agentic.platform.PlatformErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.paymentflow.agentic.runtime.AgentRuntimeHarness.CONVERSATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Nothing secret reaches the model, the transcript, or the action trail.
 *
 * <p>These assertions are cheap and the failure they guard against is permanent. A credential
 * written into an append-only action log is not recoverable by fixing the configuration
 * afterwards — the row exists, and it exists in whatever backup ran that night. So the
 * properties are asserted rather than reasoned about.
 *
 * <p>The secrets used below are deliberately shaped like the real ones — the platform's
 * {@code sk_test_}, Razorpay's {@code rzp_test_}, this platform's {@code whsec_}, Anthropic's
 * {@code sk-ant-} — because {@link Redactor}'s value rule matches on shape, and a test using
 * the word "secret" would pass without exercising it.
 */
class AgentSecurityTest {

    private static final String PLATFORM_KEY = "sk_test_averyrealisticlookingplatformkey";
    private static final String RAZORPAY_SECRET = "rzp_test_averyrealisticrazorpaysecret";
    private static final String WEBHOOK_SECRET = "whsec_averyrealisticwebhooksigningsecret";
    private static final String LLM_KEY = "sk-ant-averyrealisticanthropickey";
    private static final String INTERNAL_HMAC = "dev-only-insecure-shared-secret-change-me";

    /** Captures whatever the runtime would have sent to a provider, without sending it. */
    private static final class CapturingLlmClient implements LlmClient {

        private final List<LlmRequest> requests = new ArrayList<>();

        @Override
        public String providerName() {
            return "capturing";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            requests.add(request);
            return LlmResponse.text("Nothing to do.", "capturing");
        }

        String everythingSent() {
            return requests.stream()
                    .map(request -> request.systemPrompt() + " " + request.messages() + " "
                            + request.tools() + " " + request.model())
                    .reduce("", (left, right) -> left + " " + right);
        }
    }

    @Test
    @DisplayName("no credential appears anywhere in what is sent to the model")
    void noCredentialReachesTheProvider() {
        AgentRuntimeHarness harness = new AgentRuntimeHarness();
        CapturingLlmClient capturing = new CapturingLlmClient();
        harness.withLlmClient(capturing, secretBearingProperties());

        harness.runtime.handleUserMessage(harness.caller(), CONVERSATION, "show me your teas");

        String sent = capturing.everythingSent();
        assertThat(sent)
                .doesNotContain(PLATFORM_KEY)
                .doesNotContain(RAZORPAY_SECRET)
                .doesNotContain(WEBHOOK_SECRET)
                .doesNotContain(LLM_KEY)
                .doesNotContain(INTERNAL_HMAC);
    }

    @Test
    @DisplayName("the system prompt states no financial threshold — those live in policy configuration")
    void promptCarriesNoThresholds() {
        String prompt = new SystemPrompt().text();

        // The committed policy defaults. A prompt that repeated any of them would be a second,
        // weaker copy of a rule the engine already enforces — and the two would drift.
        assertThat(prompt)
                .doesNotContain("5000000")
                .doesNotContain("100000")
                .doesNotContain("2000000")
                .doesNotContain("50,000")
                .doesNotContain("₹");
    }

    @Test
    @DisplayName("the tool definitions shown to the model carry no credential and no transport")
    void toolDefinitionsAreClean() {
        AgentRuntimeHarness harness = new AgentRuntimeHarness();

        String definitions = harness.registry.llmDefinitions().toString();

        assertThat(definitions)
                .doesNotContain("sk_test_")
                .doesNotContain("rzp_")
                .doesNotContain("whsec_")
                .doesNotContain("apiKey")
                .doesNotContain("secret");
    }

    @Test
    @DisplayName("a credential a customer pastes into the chat is redacted before it is stored")
    void pastedCredentialIsRedactedBeforePersistence() {
        AgentRuntimeHarness harness = new AgentRuntimeHarness();

        harness.runtime.handleUserMessage(harness.caller(), CONVERSATION,
                "here is my key " + PLATFORM_KEY + " please use it");

        assertThat(harness.transcript).isNotEmpty();
        String stored = harness.transcript.stream()
                .map(ConversationMessage::getContent)
                .reduce("", (left, right) -> left + " " + right);
        assertThat(stored).doesNotContain(PLATFORM_KEY).contains(Redactor.REDACTED);
    }

    @Test
    @DisplayName("a provider error that echoes a credential back is redacted before it is recorded")
    void providerErrorTextIsRedacted() {
        AgentRuntimeHarness harness = new AgentRuntimeHarness();
        when(harness.platform.createPayment(any(), anyString(), anyLong(), any(), any(), any(), any()))
                .thenThrow(new PaymentFlowClientException(
                        PlatformErrorCode.of("UNAUTHORIZED", 401, "rejected"),
                        "The key " + PLATFORM_KEY + " is not valid.", "req_1", "corr_1"));

        AgentTurnResult result = harness.runtime.handleUserMessage(harness.caller(), CONVERSATION,
                "buy me some tea");

        AgentTurnResult.ActionSummary payment = result.actions().stream()
                .filter(action -> action.toolName().equals("complete_checkout"))
                .findFirst()
                .orElseThrow();
        assertThat(payment.message()).doesNotContain(PLATFORM_KEY).contains(Redactor.REDACTED);
    }

    @Test
    @DisplayName("a tool result summary redacts a credential whatever field it arrived in")
    void actionSummariesAreRedacted() {
        // The threat is not a field called "apiKey" — it is a model putting a key in a field
        // called "query", which only the value rule catches.
        assertThat(Redactor.summarise(Map.of("query", PLATFORM_KEY)))
                .doesNotContain(PLATFORM_KEY)
                .contains(Redactor.REDACTED);
        assertThat(Redactor.summarise(Map.of("note", RAZORPAY_SECRET)))
                .doesNotContain(RAZORPAY_SECRET);
        assertThat(Redactor.summarise(Map.of("description", LLM_KEY)))
                .doesNotContain(LLM_KEY);
        assertThat(Redactor.summarise(Map.of("metadata", WEBHOOK_SECRET)))
                .doesNotContain(WEBHOOK_SECRET);
        // OpenAI key shapes (project, service-account, and the legacy long form) — added with
        // the OpenAI provider so a key can never reach an action trace whatever field it is in.
        assertThat(Redactor.summarise(Map.of("q", "sk-proj-abcDEF123_ghiJKL456mnoPQR789stu")))
                .doesNotContain("sk-proj-abcDEF123").contains(Redactor.REDACTED);
        assertThat(Redactor.summarise(Map.of("q", "sk-svcacct-abcDEF123ghiJKL456mnoPQR")))
                .doesNotContain("sk-svcacct-abcDEF123");
        assertThat(Redactor.summarise(Map.of("q", "sk-" + "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8S9t0")))
                .contains(Redactor.REDACTED);
    }

    @Test
    @DisplayName("an absent provider credential leaks nothing — there is nothing configured to leak")
    void absentProviderCredentialLeaksNothing() {
        AgenticProperties properties = AgentRuntimeHarness.defaultProperties();

        assertThat(properties.razorpay().isConfigured()).isFalse();
        assertThat(properties.llm().isConfigured()).isFalse();
        assertThat(properties.razorpay().keySecret()).isEmpty();
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────────────

    /** Properties carrying every kind of secret this service can hold, all of them real-shaped. */
    private static AgenticProperties secretBearingProperties() {
        AgenticProperties base = AgentRuntimeHarness.defaultProperties();
        return new AgenticProperties(
                new AgenticProperties.Platform(base.platform().baseUri(), PLATFORM_KEY, 2000, 10000),
                base.policy(),
                base.checkout(),
                new AgenticProperties.Llm("anthropic", "http://llm.test", LLM_KEY, "claude-opus-5",
                        16000, 0.2, 30000, 8, 120000, "", ""),
                new AgenticProperties.Razorpay(true, "https://api.razorpay.test", "rzp_test_keyid",
                        RAZORPAY_SECRET, 2000, 8000, "decline"),
                base.demo());
    }
}
