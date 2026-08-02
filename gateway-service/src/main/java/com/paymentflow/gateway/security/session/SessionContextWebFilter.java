package com.paymentflow.gateway.security.session;

import com.paymentflow.common.error.CommonErrorCode;
import com.paymentflow.common.security.InternalContextHeaders;
import com.paymentflow.common.security.InternalContextProperties;
import com.paymentflow.common.security.InternalContextSigner;
import com.paymentflow.common.security.InternalPrincipal;
import com.paymentflow.common.security.MerchantContext;
import com.paymentflow.common.security.MerchantContextAuthenticationToken;
import com.paymentflow.gateway.security.ApiScopes;
import com.paymentflow.gateway.security.GatewayErrorResponseWriter;
import com.paymentflow.gateway.security.apikey.ApiKeyFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The gateway's developer-portal authentication path (M23.0, D183): turns a validated
 * identity-service session JWT into the same HMAC-signed internal merchant context the
 * API-key path already asserts, so the portal can read and write the public {@code /v1}
 * tier without a single service downstream learning that sessions exist.
 *
 * <p><b>Why this rather than a {@code /api/v1} mirror (D182).</b> Five of the six services
 * behind {@code /v1} have no OAuth2 resource server at all — {@code InternalContextFilter}
 * is their only authentication (D133, D100). Making them reachable by a browser session
 * the other way would have meant a resource server, a JWKS dependency and roughly twenty
 * duplicated controller methods in each, plus a second response contract that none of
 * M21's three OpenAPI gates cover. This filter is the whole change instead, and every
 * {@code /v1} endpoint — including the ones M24 needs and nobody has written a screen for
 * yet — is reachable the moment it merges.
 *
 * <p><b>Structurally a sibling of {@code ApiKeyAuthenticationWebFilter}</b>, ordered
 * immediately after it (+21) so the two can never both claim a request: that filter returns
 * the exchange untouched for anything that is not {@code sk_}/{@code pk_}-shaped, and this
 * one returns it untouched for anything that is not a JWT. Every failure here is
 * fail-closed and explicit; nothing falls through to "try the other path".
 *
 * <p><b>Mode is selected by the session, and that is not a relaxation of the invariant
 * (D184).</b> "Mode is bound to the key" exists to stop a key holder — possibly a third
 * party handed a test key — from reaching live data. A session's owner is entitled to both
 * modes of their own merchant by definition, which is why the {@code /api/v1} routes have
 * deliberately honoured the dashboard's {@code X-PF-Mode} toggle since M16.2. On the
 * API-key path the header is still stripped and mode still comes from the key; here it is
 * read, validated against the two legal values, and then <b>removed from the forwarded
 * request</b> so the only thing any downstream service ever trusts is the signed context.
 */
@Component
public class SessionContextWebFilter implements WebFilter, Ordered {

    /**
     * Exchange attribute carrying the user id this filter authenticated (M23.0). Read by
     * {@code RateLimiterConfig} to bucket portal traffic per user rather than per address.
     *
     * <p>Deliberately <b>not</b> the shape {@code ApiRequestLoggingFilter} and
     * {@code ApiKeyRateLimitWebFilter} look for (D186): dashboard traffic is not API traffic.
     * A merchant's {@code GET /v1/request_logs} exists to debug <em>their integration</em>,
     * and filling it with their own dashboard's reads would destroy the one signal it
     * carries. Their per-key quota is likewise theirs to spend on their own software, not on
     * looking at a screen.
     */
    public static final String RESOLVED_SESSION_USER_ATTRIBUTE =
            SessionContextWebFilter.class.getName() + ".resolvedSessionUser";

    /** Selects the data plane a portal request reads. Consumed here and never forwarded. */
    public static final String MODE_HEADER = "X-PF-Mode";

    static final String MODE_TEST = "test";
    static final String MODE_LIVE = "live";

    private static final Logger log = LoggerFactory.getLogger(SessionContextWebFilter.class);

    private static final String PUBLIC_TIER_PREFIX = "/v1/";
    /**
     * The one {@code /v1} path that needs no credential (M17.8) — reference data identical
     * for every caller, declared {@code security: []} in the published document. A session
     * must not be able to turn it into an authenticated endpoint, which is what would happen
     * if a not-yet-onboarded user's browser started receiving a 403 from it.
     */
    private static final String TEST_CARDS_PATH_PREFIX = "/v1/test/cards";

    private final ReactiveJwtDecoder jwtDecoder;
    private final SessionMerchantResolver merchantResolver;
    private final InternalContextSigner signer;
    private final InternalContextProperties internalContextProperties;
    private final GatewayErrorResponseWriter errorWriter;
    private final Clock clock;

    public SessionContextWebFilter(ReactiveJwtDecoder jwtDecoder, SessionMerchantResolver merchantResolver,
                                   InternalContextSigner signer,
                                   InternalContextProperties internalContextProperties,
                                   GatewayErrorResponseWriter errorWriter) {
        this.jwtDecoder = jwtDecoder;
        this.merchantResolver = merchantResolver;
        this.signer = signer;
        this.internalContextProperties = internalContextProperties;
        this.errorWriter = errorWriter;
        this.clock = Clock.systemUTC();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 21;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith(PUBLIC_TIER_PREFIX) || path.startsWith(TEST_CARDS_PATH_PREFIX)) {
            return chain.filter(exchange);
        }

        String credential = extractBearer(exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        if (ApiKeyFormat.classify(credential) != ApiKeyFormat.CredentialType.JWT) {
            return chain.filter(exchange);
        }

        String requestedMode = resolveMode(exchange);
        if (requestedMode == null) {
            return errorWriter.write(exchange, CommonErrorCode.BAD_REQUEST,
                    "The " + MODE_HEADER + " header must be either \"" + MODE_TEST + "\" or \"" + MODE_LIVE + "\".");
        }

        return jwtDecoder.decode(credential)
                .flatMap(jwt -> authenticate(exchange, chain, jwt, requestedMode))
                .onErrorResume(MerchantNotOnboardedException.class, e -> errorWriter.write(exchange,
                        CommonErrorCode.FORBIDDEN,
                        "This account is not associated with a merchant yet."))
                .onErrorResume(InvalidSessionException.class,
                        e -> errorWriter.write(exchange, CommonErrorCode.UNAUTHORIZED))
                // A malformed, expired or wrongly-issued token is an authentication failure.
                // Anything else reaching here is merchant-service being unreachable or slow —
                // the circuit breaker opening, or the time limiter firing. Those are not the
                // caller's fault and must not be reported as though the caller's credential
                // were bad, or the portal would sign a user out during a backend blip.
                .onErrorResume(org.springframework.security.oauth2.jwt.JwtException.class,
                        e -> errorWriter.write(exchange, CommonErrorCode.UNAUTHORIZED))
                .onErrorResume(error -> {
                    log.warn("Session merchant resolution failed; refusing the request", error);
                    return errorWriter.write(exchange, CommonErrorCode.SERVICE_UNAVAILABLE);
                });
    }

    private Mono<Void> authenticate(ServerWebExchange exchange, WebFilterChain chain, Jwt jwt, String mode) {
        UUID userId;
        try {
            userId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            // identity-service issues UUID subjects. Anything else is a token this platform
            // did not mint for a portal user, whatever its signature says.
            return Mono.error(new InvalidSessionException());
        }

        return merchantResolver.resolve(userId)
                .flatMap(merchant -> proceed(exchange, chain, userId, merchant, mode));
    }

    private Mono<Void> proceed(ServerWebExchange exchange, WebFilterChain chain, UUID userId,
                               SessionMerchantResult merchant, String mode) {
        exchange.getAttributes().put(RESOLVED_SESSION_USER_ATTRIBUTE, userId);

        // One user per merchant today (§13-Q2), and that user is the owner — so the session
        // gets the whole vocabulary. Enumerated rather than the "*" wildcard on purpose:
        // the wildcard would make the gateway's scope check stop running on exactly the
        // traffic a human drives, and a future member role wants to be a subset of this set
        // rather than a second mechanism.
        Set<String> scopes = ApiScopes.ALL;
        String scopesCsv = String.join(InternalContextHeaders.SCOPES_DELIMITER, scopes);
        long issuedAtEpochSecond = clock.instant().getEpochSecond();
        String signature = signer.sign(internalContextProperties.secret(), merchant.merchantId().toString(), mode,
                InternalPrincipal.SESSION, userId.toString(), null, scopesCsv,
                merchant.contactEmail(), merchant.webhookUrl(), issuedAtEpochSecond);

        ServerHttpRequest.Builder requestBuilder = exchange.getRequest().mutate()
                // Same rule as the API-key path: the credential is replaced, not supplemented.
                // A downstream OAuth2 resource server that saw this JWT would authenticate the
                // *user* rather than the merchant context, and payment-service — the one /v1
                // service that has such a resource server — would then scope the request by a
                // path this filter deliberately does not take.
                .headers(headers -> {
                    headers.remove(HttpHeaders.AUTHORIZATION);
                    headers.remove(MODE_HEADER);
                })
                .header(InternalContextHeaders.MERCHANT_ID, merchant.merchantId().toString())
                .header(InternalContextHeaders.MODE, mode)
                .header(InternalContextHeaders.PRINCIPAL, InternalPrincipal.SESSION.wireValue())
                .header(InternalContextHeaders.USER_ID, userId.toString())
                .header(InternalContextHeaders.SCOPES, scopesCsv)
                .header(InternalContextHeaders.ISSUED_AT, Long.toString(issuedAtEpochSecond))
                .header(InternalContextHeaders.SIGNATURE, signature);
        if (merchant.contactEmail() != null) {
            requestBuilder.header(InternalContextHeaders.CONTACT_EMAIL, merchant.contactEmail());
        }
        if (merchant.webhookUrl() != null) {
            requestBuilder.header(InternalContextHeaders.WEBHOOK_URL, merchant.webhookUrl());
        }

        Authentication authentication = new MerchantContextAuthenticationToken(
                MerchantContext.forSession(merchant.merchantId(), mode, userId, scopes,
                        merchant.contactEmail(), merchant.webhookUrl()));

        return chain.filter(exchange.mutate().request(requestBuilder.build()).build())
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
    }

    /**
     * @return the validated mode, or {@code null} if the header named something that is not a
     *         mode. Absent means {@code test} — the safe default, and the one a developer
     *         opening the portal for the first time wants: a mis-sent header must never land
     *         a human on live data by accident.
     */
    private static String resolveMode(ServerWebExchange exchange) {
        String requested = exchange.getRequest().getHeaders().getFirst(MODE_HEADER);
        if (requested == null || requested.isBlank()) {
            return MODE_TEST;
        }
        String normalized = requested.trim().toLowerCase(Locale.ROOT);
        return (MODE_TEST.equals(normalized) || MODE_LIVE.equals(normalized)) ? normalized : null;
    }

    private static String extractBearer(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        return authorizationHeader.substring(7).trim();
    }

    /** A token that decoded but does not describe a portal user. */
    static final class InvalidSessionException extends RuntimeException {
        InvalidSessionException() {
            super("The session token does not identify a portal user.");
        }
    }
}
