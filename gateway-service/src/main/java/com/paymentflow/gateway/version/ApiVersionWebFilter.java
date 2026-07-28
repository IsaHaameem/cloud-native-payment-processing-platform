package com.paymentflow.gateway.version;

import com.paymentflow.common.dto.version.ApiVersion;
import com.paymentflow.common.dto.version.ApiVersions;
import com.paymentflow.common.error.CommonErrorCode;
import com.paymentflow.gateway.security.GatewayErrorResponseWriter;
import com.paymentflow.gateway.security.apikey.ApiKeyAuthenticationWebFilter;
import com.paymentflow.gateway.security.apikey.ApiKeyVerifyResult;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Resolves the API revision for every public request, and rewrites the request into the
 * shape the services speak (M21.5, §4.10).
 *
 * <p>Runs after {@link ApiKeyAuthenticationWebFilter} (order +20) because it needs the
 * merchant's pin, which arrives on the key-verification result, and after the rate limiter
 * (+30) because a request that is going to be rejected for rate limiting should not first
 * be transformed. Everything downstream — including the services themselves — speaks only
 * the current revision; that is the point of resolving here.
 *
 * <p><b>What this filter does and does not touch.</b> It resolves the version, sets the
 * response headers that describe it, and applies request-side transformations. The
 * response-side transformation belongs to {@link ApiVersionResponseBodyFilter}, because
 * rewriting a response body requires decorating the response before the route runs and is a
 * genuinely different mechanism from mutating a request.
 */
@Component
public class ApiVersionWebFilter implements WebFilter, Ordered {

    /** The request header a caller sends to override their pin for one call. */
    public static final String VERSION_HEADER = "PaymentFlow-Version";

    /**
     * The resolved revision, for the response-body filter and anything else downstream.
     * An attribute rather than a header because it is gateway-internal state — services
     * must not be able to see or act on a version (§4.10 puts transformation at the edge).
     */
    public static final String RESOLVED_VERSION_ATTRIBUTE =
            ApiVersionWebFilter.class.getName() + ".RESOLVED_VERSION";

    /** RFC 9110 date, which is what `Deprecation` and `Sunset` are specified to carry. */
    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
                    .withZone(ZoneOffset.UTC);

    private final ApiVersionResolver resolver;
    private final ApiTransformationRegistry registry;
    private final GatewayErrorResponseWriter errorWriter;

    public ApiVersionWebFilter(ApiVersionResolver resolver, ApiTransformationRegistry registry,
                               GatewayErrorResponseWriter errorWriter) {
        this.resolver = resolver;
        this.registry = registry;
        this.errorWriter = errorWriter;
    }

    @Override
    public int getOrder() {
        // After the rate limiter (+30). A request that will be refused for being over its
        // limit should not have been transformed first, and a 429 has no versioned body.
        return Ordered.HIGHEST_PRECEDENCE + 40;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requested = exchange.getRequest().getHeaders().getFirst(VERSION_HEADER);
        String pin = merchantPin(exchange);

        ApiVersion resolved;
        try {
            resolved = resolver.resolve(requested, pin);
        } catch (UnsupportedApiVersionException e) {
            return errorWriter.write(exchange, CommonErrorCode.UNSUPPORTED_API_VERSION, e.getMessage());
        }

        exchange.getAttributes().put(RESOLVED_VERSION_ATTRIBUTE, resolved);
        applyResponseHeaders(exchange, resolved);

        List<ApiTransformation> transformations = registry.forRequest(resolved);
        if (transformations.isEmpty()) {
            // The overwhelmingly common path: the caller is on the current revision and
            // nothing has to be rewritten in either direction.
            return chain.filter(exchange);
        }
        return chain.filter(transformRequest(exchange, transformations));
    }

    /**
     * Applies each transformation's request-side rewrite, oldest revision first, walking the
     * caller's shape forward into the current one.
     */
    private ServerWebExchange transformRequest(ServerWebExchange exchange,
                                               List<ApiTransformation> transformations) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        MultiValueMap<String, String> params = request.getQueryParams();
        for (ApiTransformation transformation : transformations) {
            params = transformation.transformRequestParams(path, params);
        }
        if (params.equals(request.getQueryParams())) {
            return exchange;
        }

        // Rebuilt through UriComponentsBuilder rather than by string concatenation so that
        // values needing encoding (the `metadata[key]=value` filter, D142) survive intact.
        org.springframework.web.util.UriComponentsBuilder uri =
                org.springframework.web.util.UriComponentsBuilder.fromUri(request.getURI())
                        .replaceQueryParams(params);
        return exchange.mutate().request(request.mutate().uri(uri.build(true).toUri()).build()).build();
    }

    /**
     * Tells the caller which revision answered them, and — when that revision is superseded
     * — that it will not be answered forever.
     *
     * <p>The headers go on before the route runs, because once the response has started
     * committing it is too late to add any. {@code Deprecation} and {@code Sunset} are the
     * standard spellings, and the {@code Link} to the versioning documentation is what makes
     * them actionable rather than merely alarming.
     */
    private void applyResponseHeaders(ServerWebExchange exchange, ApiVersion resolved) {
        var headers = exchange.getResponse().getHeaders();
        headers.set(VERSION_HEADER, resolved.toString());

        if (!ApiVersions.isSuperseded(resolved)) {
            return;
        }
        // `Deprecation: true` is the RFC 8594 companion form; a date would imply the
        // revision became deprecated at a moment we do not record per merchant.
        headers.set("Deprecation", "true");
        ApiVersions.sunsetOf(resolved).ifPresent(sunset ->
                headers.set("Sunset", HTTP_DATE.format(sunset.atStartOfDay(ZoneOffset.UTC))));
        headers.add("Link", "<https://docs.paymentflow.dev/versioning>; rel=\"deprecation\"");
    }

    /** The merchant's pinned revision, or null for an unauthenticated or unpinned caller. */
    private static String merchantPin(ServerWebExchange exchange) {
        Object attribute = exchange.getAttributes()
                .get(ApiKeyAuthenticationWebFilter.RESOLVED_KEY_CONTEXT_ATTRIBUTE);
        return (attribute instanceof ApiKeyVerifyResult result) ? result.pinnedApiVersion() : null;
    }
}
