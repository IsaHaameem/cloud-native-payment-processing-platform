package com.paymentflow.payment.merchant;

import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * Forwards the original caller's JWT to merchant-service, so its existing
 * {@code GET /api/v1/merchants/me} (which derives ownership from the token subject,
 * per M4) resolves the same merchant a browser client calling it directly would see —
 * no new merchant-service endpoint or service-to-service credential needed.
 */
@Configuration
public class FeignAuthorizationForwardingConfig {

    @Bean
    public RequestInterceptor authorizationForwardingInterceptor() {
        return requestTemplate -> currentAuthorizationHeader()
                .ifPresent(value -> requestTemplate.header(HttpHeaders.AUTHORIZATION, value));
    }

    private static Optional<String> currentAuthorizationHeader() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return Optional.empty();
        }
        return Optional.ofNullable(attributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION));
    }
}
