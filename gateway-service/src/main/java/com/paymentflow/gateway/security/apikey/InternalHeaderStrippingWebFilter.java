package com.paymentflow.gateway.security.apikey;

import com.paymentflow.common.security.InternalContextHeaders;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * Unconditionally strips every inbound {@code X-PF-Internal-*} header before anything
 * else runs (M15, §4.3 step 1) — the milestone's own explicit completion criterion: a
 * client-supplied internal-context header can never reach a downstream service. Fires
 * on every request regardless of credential type or route, since the fields it
 * protects ({@code ApiKeyAuthenticationWebFilter} only ever *adds* fresh, signed
 * copies on the API-key path) would otherwise pass straight through unauthenticated
 * requests and JWT-authenticated requests untouched.
 */
@Component
public class InternalHeaderStrippingWebFilter implements WebFilter, Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest original = exchange.getRequest();
        List<String> internalHeaderNames = new ArrayList<>();
        original.getHeaders().forEach((name, values) -> {
            if (name.regionMatches(true, 0, InternalContextHeaders.HEADER_PREFIX, 0,
                    InternalContextHeaders.HEADER_PREFIX.length())) {
                internalHeaderNames.add(name);
            }
        });

        if (internalHeaderNames.isEmpty()) {
            return chain.filter(exchange);
        }

        ServerHttpRequest stripped = original.mutate()
                .headers(headers -> internalHeaderNames.forEach(headers::remove))
                .build();
        return chain.filter(exchange.mutate().request(stripped).build());
    }
}
