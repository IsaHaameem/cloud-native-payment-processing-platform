package com.paymentflow.gateway.config;

import com.paymentflow.gateway.security.apikey.ApiKeyFormat;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * Redis-backed rate-limiting key resolution: authenticated calls are keyed per user
 * (so one busy user cannot exhaust another's quota), everything else — most
 * importantly the unauthenticated {@code /api/v1/auth/**} endpoints, the ones most
 * exposed to brute force — falls back to the client's remote address.
 *
 * <p><b>M20.5 (D146): API-key traffic is deliberately excluded here</b> and owned by
 * {@link com.paymentflow.gateway.ratelimit.ApiKeyRateLimitWebFilter} instead. Returning an
 * empty key makes the built-in {@code RequestRateLimiter} skip the request
 * ({@code deny-empty-key: false} in {@code application.yaml}), so the two limiters never
 * both count the same request.
 *
 * <p>That exclusion is the point rather than an optimisation. §14 recorded the gap this
 * closes: before M20.5, API-key traffic fell into the shared IP bucket, so a merchant's
 * server — one address, high volume, entirely legitimate — competed for an allowance sized
 * for browsers, and a per-key limit could never exceed it however generously configured.
 */
@Configuration
public class RateLimiterConfig {

    @Bean
    public KeyResolver rateLimitKeyResolver() {
        return exchange -> {
            if (isApiKeyRequest(exchange)) {
                return Mono.empty();
            }
            return ReactiveSecurityContextHolder.getContext()
                    .map(SecurityContext::getAuthentication)
                    .filter(JwtAuthenticationToken.class::isInstance)
                    .map(authentication -> "user:" + authentication.getName())
                    .switchIfEmpty(Mono.fromSupplier(() -> "ip:" + clientIp(exchange)));
        };
    }

    /**
     * Classified from the credential's shape alone — a pure function with no I/O (M15's
     * {@code ApiKeyFormat}), so this resolver stays as cheap as it was before and does not
     * depend on authentication having run yet.
     */
    private static boolean isApiKeyRequest(ServerWebExchange exchange) {
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return false;
        }
        String credential = authorization.substring(BEARER_PREFIX.length()).trim();
        return ApiKeyFormat.classify(credential) == ApiKeyFormat.CredentialType.API_KEY;
    }

    private static final String BEARER_PREFIX = "Bearer ";

    private static String clientIp(ServerWebExchange exchange) {
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        return (remoteAddress != null && remoteAddress.getAddress() != null)
                ? remoteAddress.getAddress().getHostAddress()
                : "unknown";
    }
}
