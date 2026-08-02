package com.paymentflow.gateway.security.session;

import com.paymentflow.gateway.config.MerchantServiceProperties;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Calls merchant-service's {@code /internal/v1/merchants/by-owner/{ownerUserId}} on a cache
 * miss (M23.0) — the session path's counterpart to {@link
 * com.paymentflow.gateway.security.apikey.ApiKeyVerificationClient}, built the same way and
 * for the same reasons: {@link WebClient} because the gateway is reactive end to end, and
 * {@link WebClient#builder()} directly because Spring Cloud Gateway's reactive starter does
 * not register a {@code WebClient.Builder} bean to inject.
 */
@Component
public class SessionMerchantLookupClient {

    private final WebClient webClient;

    public SessionMerchantLookupClient(MerchantServiceProperties merchantServiceProperties) {
        this.webClient = WebClient.builder().baseUrl(merchantServiceProperties.baseUri()).build();
    }

    public Mono<SessionMerchantResult> lookup(UUID ownerUserId) {
        return webClient.get()
                .uri("/internal/v1/merchants/by-owner/{ownerUserId}", ownerUserId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError,
                        response -> Mono.error(new MerchantNotOnboardedException()))
                .bodyToMono(SessionMerchantResult.class);
    }
}
