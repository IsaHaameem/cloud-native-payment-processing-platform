package com.paymentflow.agentic.llm;

import com.paymentflow.agentic.config.AgenticProperties;
import com.paymentflow.agentic.config.RestClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Chooses which {@link LlmClient} the runtime gets, from configuration alone.
 *
 * <p><b>An unconfigured credential selects the scripted client rather than failing startup.</b>
 * That is the behaviour {@code application.yaml} already promises, and it is what lets CI run
 * the whole agent pipeline — and a reviewer clone the repository and see a working demo —
 * without anyone having a key. The alternative, refusing to start, would make every test that
 * touches the runtime require a paid credential to be meaningful.
 *
 * <p>The selection is logged at startup, because "which model answered?" is the first question
 * anyone asks of an agent demo, and a service that silently fell back to a script while looking
 * like it was reasoning would be actively misleading.
 */
@Configuration
public class LlmClientConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmClientConfig.class);

    private static final String PROVIDER_ANTHROPIC = "anthropic";
    private static final String PROVIDER_OPENAI = "openai";

    /**
     * Always present, and always the fallback. Registered as its own bean so tests can inject it
     * and register scenarios on it without reaching through the {@link LlmClient} interface.
     */
    @Bean
    public ScriptedLlmClient scriptedLlmClient(ObjectMapper objectMapper) {
        return new ScriptedLlmClient(objectMapper);
    }

    @Bean
    public LlmClient llmClient(AgenticProperties properties,
                               @Qualifier(RestClientConfig.LLM_CLIENT) RestClient llmRestClient,
                               ObjectMapper objectMapper,
                               ScriptedLlmClient scriptedLlmClient) {
        AgenticProperties.Llm llm = properties.llm();

        if (!llm.isConfigured()) {
            log.warn("No language-model credential is configured for provider '{}'; the agent will run the "
                    + "deterministic scripted client. Set ANTHROPIC_API_KEY, or OPENAI_API_KEY with "
                    + "AGENTIC_LLM_PROVIDER=openai, to use a real model.", llm.provider());
            return scriptedLlmClient;
        }

        if (PROVIDER_OPENAI.equalsIgnoreCase(llm.provider())) {
            log.info("Agent runtime will use the openai provider with model {}.", llm.activeModel());
            return new OpenAiLlmClient(llmRestClient, properties, objectMapper);
        }
        if (PROVIDER_ANTHROPIC.equalsIgnoreCase(llm.provider())) {
            log.info("Agent runtime will use the anthropic provider with model {}.", llm.activeModel());
            return new AnthropicLlmClient(llmRestClient, properties);
        }

        // Named rather than guessed at. Silently substituting a different provider for one an
        // operator asked for is the kind of helpfulness that hides a misconfiguration until it
        // matters.
        log.warn("Language-model provider '{}' has no adapter in this service; falling back to the "
                + "scripted client. The implemented providers are '{}' and '{}'.",
                llm.provider(), PROVIDER_ANTHROPIC, PROVIDER_OPENAI);
        return scriptedLlmClient;
    }
}
