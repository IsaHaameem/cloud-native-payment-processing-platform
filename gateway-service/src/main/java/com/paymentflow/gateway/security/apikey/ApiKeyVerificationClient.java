package com.paymentflow.gateway.security.apikey;

import com.paymentflow.gateway.config.MerchantServiceProperties;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Calls merchant-service's {@code /internal/v1/api-keys/verify} on a cache miss
 * (M15, §4.3 step 3). Non-blocking by construction ({@link WebClient}), unlike
 * payment-service's OpenFeign-based {@code MerchantClient} (M8) — the gateway is
 * reactive end to end, so this is the first genuinely non-blocking cross-service call
 * in the platform rather than a blocking call offloaded to a dedicated thread pool.
 *
 * <p>Built via {@link WebClient#builder()} directly rather than an injected
 * {@code WebClient.Builder} bean: Spring Cloud Gateway's reactive starter does not
 * pull in Boot's {@code WebClientAutoConfiguration} (confirmed — the bean genuinely
 * isn't there, not a wiring mistake), so gateway-service has no such bean to inject.
 */
@Component
public class ApiKeyVerificationClient {

    private final WebClient webClient;

    public ApiKeyVerificationClient(MerchantServiceProperties merchantServiceProperties) {
        this.webClient = WebClient.builder().baseUrl(merchantServiceProperties.baseUri()).build();
    }

    public Mono<ApiKeyVerifyResult> verify(String rawKey) {
        return webClient.post()
                .uri("/internal/v1/api-keys/verify")
                .bodyValue(new VerifyRequestBody(rawKey))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, response -> Mono.error(new InvalidApiKeyException()))
                .bodyToMono(ApiKeyVerifyResult.class);
    }

    private record VerifyRequestBody(String apiKey) {
    }
}
