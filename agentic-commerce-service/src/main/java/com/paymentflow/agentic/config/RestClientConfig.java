package com.paymentflow.agentic.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The three outbound HTTP clients this service needs — the payment platform, the LLM
 * provider, and Razorpay — each built with its own timeouts.
 *
 * <p>{@link RestClient} rather than a new HTTP dependency: it ships with
 * {@code spring-boot-starter-web}, which this module already has, so all three integrations
 * cost zero additional dependencies. The repository's rule is to look for an existing
 * equivalent before adding one; this is that check having been made.
 *
 * <p>Timeouts are per-client and set at the socket. A socket that gives up first is the
 * pattern payment-service established for its own Feign clients: the application-level budget
 * then exists to catch what the socket cannot see, rather than being the only thing between a
 * hung peer and a stuck thread.
 */
@Configuration
public class RestClientConfig {

    /** Bean names each consumer injects by qualifier; three clients of one type need distinguishing. */
    public static final String PLATFORM_CLIENT = "platformRestClient";
    public static final String LLM_CLIENT = "llmRestClient";
    public static final String RAZORPAY_CLIENT = "razorpayRestClient";

    /**
     * Short enough that an unreachable host fails fast rather than occupying a thread for the
     * whole read budget. Applied to the LLM client, whose read budget is necessarily generous
     * because it covers the model thinking.
     */
    private static final int LLM_CONNECT_TIMEOUT_MS = 3000;

    @Bean(PLATFORM_CLIENT)
    public RestClient platformRestClient(AgenticProperties properties) {
        AgenticProperties.Platform platform = properties.platform();
        return RestClient.builder()
                .baseUrl(platform.baseUri())
                .requestFactory(requestFactory(platform.connectTimeoutMs(), platform.readTimeoutMs()))
                .build();
    }

    @Bean(LLM_CLIENT)
    public RestClient llmRestClient(AgenticProperties properties) {
        AgenticProperties.Llm llm = properties.llm();
        // activeBaseUri(), not baseUri(): an explicit AGENTIC_LLM_BASE_URI still wins, but a
        // blank one now resolves to the selected provider's host rather than always Anthropic's.
        return RestClient.builder()
                .baseUrl(llm.activeBaseUri())
                .requestFactory(requestFactory(LLM_CONNECT_TIMEOUT_MS, llm.timeoutMs()))
                .build();
    }

    @Bean(RAZORPAY_CLIENT)
    public RestClient razorpayRestClient(AgenticProperties properties) {
        AgenticProperties.Razorpay razorpay = properties.razorpay();
        return RestClient.builder()
                .baseUrl(razorpay.baseUri())
                .requestFactory(requestFactory(razorpay.connectTimeoutMs(), razorpay.readTimeoutMs()))
                .build();
    }

    /**
     * {@link SimpleClientHttpRequestFactory} — the JDK's own client, configured with both
     * timeouts.
     *
     * <p>Boot 4 moved {@code ClientHttpRequestFactoryBuilder} out of {@code spring-boot} and
     * into the separate {@code spring-boot-http-client} module, so reaching for it here would
     * mean adding a dependency purely to detect a client library this module does not have
     * anyway. notification-service already sets the precedent in {@code WebhookClientConfig}:
     * plain {@code spring-web}, no extra artifact, and the two timeouts that actually matter
     * set explicitly rather than inherited from a default nobody reads.
     */
    private static ClientHttpRequestFactory requestFactory(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return factory;
    }
}
