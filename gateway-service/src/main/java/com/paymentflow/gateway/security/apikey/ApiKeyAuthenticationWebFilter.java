package com.paymentflow.gateway.security.apikey;

import com.paymentflow.common.error.CommonErrorCode;
import com.paymentflow.common.security.InternalContextHeaders;
import com.paymentflow.common.security.InternalContextProperties;
import com.paymentflow.common.security.InternalContextSigner;
import com.paymentflow.common.security.MerchantContext;
import com.paymentflow.common.security.MerchantContextAuthenticationToken;
import com.paymentflow.gateway.security.GatewayErrorResponseWriter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The gateway's API-key authentication path (M15, §4.3 steps 2–6): detects an API-key
 * credential, resolves it (cache, falling back to a resilience-wrapped merchant-service
 * verify call), enforces scope for the route, and — on success — both authenticates
 * the reactive security context for this request and injects the HMAC-signed internal
 * context headers the request is proxied downstream with.
 *
 * <p>A JWT or absent credential is left completely untouched (returns
 * {@code chain.filter(exchange)} unchanged): the existing JWT path (V1) is not
 * refactored, only added alongside — the milestone's own highest-risk mitigation.
 * Every failure mode here is fail-closed: an API-key-shaped credential that doesn't
 * verify, lacks the required scope, or is a publishable key on a mutating route is
 * rejected outright, never silently downgraded to "try the JWT path instead."
 */
@Component
public class ApiKeyAuthenticationWebFilter implements WebFilter, Ordered {

    private static final String PAYMENTS_PATH_PREFIX = "/v1/payments";
    private static final String SCOPE_PAYMENTS_READ = "payments:read";
    private static final String SCOPE_PAYMENTS_WRITE = "payments:write";
    private static final String ERROR_CODE_INSUFFICIENT_SCOPE = "INSUFFICIENT_SCOPE";

    private final ApiKeyCacheService cacheService;
    private final ResilientApiKeyVerifier verifier;
    private final InternalContextSigner signer;
    private final InternalContextProperties internalContextProperties;
    private final GatewayErrorResponseWriter errorWriter;
    private final Clock clock;

    public ApiKeyAuthenticationWebFilter(ApiKeyCacheService cacheService, ResilientApiKeyVerifier verifier,
                                         InternalContextSigner signer, InternalContextProperties internalContextProperties,
                                         GatewayErrorResponseWriter errorWriter) {
        this.cacheService = cacheService;
        this.verifier = verifier;
        this.signer = signer;
        this.internalContextProperties = internalContextProperties;
        this.errorWriter = errorWriter;
        this.clock = Clock.systemUTC();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String rawKey = extractBearer(exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        if (ApiKeyFormat.classify(rawKey) != ApiKeyFormat.CredentialType.API_KEY) {
            return chain.filter(exchange);
        }

        String keyHash = ApiKeyFormat.sha256Hex(rawKey);
        return resolve(rawKey, keyHash)
                .flatMap(result -> authenticateAndProceed(exchange, chain, rawKey, result))
                .onErrorResume(InvalidApiKeyException.class,
                        e -> errorWriter.write(exchange, CommonErrorCode.UNAUTHORIZED));
    }

    private Mono<ApiKeyVerifyResult> resolve(String rawKey, String keyHash) {
        return cacheService.lookup(keyHash).flatMap(result -> {
            if (result instanceof ApiKeyCacheService.Hit hit) {
                return Mono.just(hit.result());
            }
            if (result instanceof ApiKeyCacheService.Negative) {
                return Mono.error(new InvalidApiKeyException());
            }
            return verifier.verify(rawKey)
                    .flatMap(verified -> cacheService.putPositive(keyHash, verified).thenReturn(verified))
                    .onErrorResume(InvalidApiKeyException.class,
                            e -> cacheService.putNegative(keyHash).then(Mono.error(e)));
        });
    }

    private Mono<Void> authenticateAndProceed(ServerWebExchange exchange, WebFilterChain chain, String rawKey,
                                              ApiKeyVerifyResult result) {
        HttpMethod method = exchange.getRequest().getMethod();
        String path = exchange.getRequest().getPath().value();
        String requiredScope = requiredScopeFor(method, path);
        Set<String> scopes = new LinkedHashSet<>(result.scopes());

        if (requiredScope != null && !scopes.contains("*") && !scopes.contains(requiredScope)) {
            return errorWriter.write(exchange, HttpStatus.FORBIDDEN, ERROR_CODE_INSUFFICIENT_SCOPE,
                    "This API key does not have the required scope: " + requiredScope);
        }
        // Defense in depth beyond scope matching: a publishable key is read-only by
        // construction (§4.3), regardless of what its scope list happens to contain.
        if (ApiKeyFormat.isPublishable(rawKey) && isMutating(method)) {
            return errorWriter.write(exchange, HttpStatus.FORBIDDEN, ERROR_CODE_INSUFFICIENT_SCOPE,
                    "Publishable keys are read-only.");
        }

        long issuedAtEpochSecond = clock.instant().getEpochSecond();
        String scopesCsv = String.join(InternalContextHeaders.SCOPES_DELIMITER, scopes);
        String signature = signer.sign(internalContextProperties.secret(), result.merchantId().toString(),
                result.mode(), result.keyId().toString(), scopesCsv, result.contactEmail(), result.webhookUrl(),
                issuedAtEpochSecond);

        ServerHttpRequest.Builder requestBuilder = exchange.getRequest().mutate()
                // The client's raw `Authorization: Bearer sk_...` must NOT reach a downstream
                // service: its OAuth2 resource server would try to decode the API key as a JWT
                // and reject the request 401 ("Malformed token") before the internal context is
                // ever consulted. From here on the signed X-PF-Internal-* headers below are the
                // request's downstream credential — replacing, not supplementing, the API key.
                .headers(headers -> headers.remove(HttpHeaders.AUTHORIZATION))
                .header(InternalContextHeaders.MERCHANT_ID, result.merchantId().toString())
                .header(InternalContextHeaders.MODE, result.mode())
                .header(InternalContextHeaders.KEY_ID, result.keyId().toString())
                .header(InternalContextHeaders.SCOPES, scopesCsv)
                .header(InternalContextHeaders.ISSUED_AT, Long.toString(issuedAtEpochSecond))
                .header(InternalContextHeaders.SIGNATURE, signature);
        if (result.contactEmail() != null) {
            requestBuilder.header(InternalContextHeaders.CONTACT_EMAIL, result.contactEmail());
        }
        if (result.webhookUrl() != null) {
            requestBuilder.header(InternalContextHeaders.WEBHOOK_URL, result.webhookUrl());
        }

        Authentication authentication = new MerchantContextAuthenticationToken(
                new MerchantContext(result.merchantId(), result.mode(), result.keyId(), scopes,
                        result.contactEmail(), result.webhookUrl()));

        return chain.filter(exchange.mutate().request(requestBuilder.build()).build())
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
    }

    /** {@code null} means "no specific scope required" — every {@code /v1} route today is under payments. */
    private static String requiredScopeFor(HttpMethod method, String path) {
        if (!path.startsWith(PAYMENTS_PATH_PREFIX)) {
            return null;
        }
        return isMutating(method) ? SCOPE_PAYMENTS_WRITE : SCOPE_PAYMENTS_READ;
    }

    private static boolean isMutating(HttpMethod method) {
        return method != HttpMethod.GET && method != HttpMethod.HEAD && method != HttpMethod.OPTIONS;
    }

    private static String extractBearer(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        return authorizationHeader.substring(7).trim();
    }
}
