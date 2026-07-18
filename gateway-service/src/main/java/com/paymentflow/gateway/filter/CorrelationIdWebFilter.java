package com.paymentflow.gateway.filter;

import com.paymentflow.common.correlation.CorrelationConstants;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Reactive equivalent of common-lib's servlet-only {@code CorrelationIdFilter} (which
 * stays inactive here — see common-lib D11). Establishes a correlation id (propagated
 * across services) and a per-hop request id for every inbound request, forwards both
 * downstream on the proxied request, and echoes the correlation id back to the caller.
 *
 * <p>Ordered ahead of Spring Security's WebFilter (registered at order -100) so the ids
 * are already present on the exchange when the security entry point / access-denied
 * handler need to read them for the {@code ApiError} envelope.
 */
@Component
public class CorrelationIdWebFilter implements WebFilter, Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        String correlationId = firstNonBlank(
                request.getHeaders().getFirst(CorrelationConstants.CORRELATION_ID_HEADER), UUID.randomUUID().toString());
        String requestId = firstNonBlank(
                request.getHeaders().getFirst(CorrelationConstants.REQUEST_ID_HEADER), UUID.randomUUID().toString());

        ServerHttpRequest mutatedRequest = request.mutate()
                .header(CorrelationConstants.CORRELATION_ID_HEADER, correlationId)
                .header(CorrelationConstants.REQUEST_ID_HEADER, requestId)
                .build();

        // beforeCommit (not a post-chain hook like doFinally/then): a streamed proxy response
        // commits headers as soon as body writing starts, well before chain.filter()'s Mono
        // completes, so anything scheduled after completion runs too late. identity-service's
        // own CorrelationIdFilter also echoes this header on its response, which the gateway's
        // proxy filter copies onto this exchange — beforeCommit fires after that copy but
        // before the wire write, so .set() (replace, not add) collapses it to one value.
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(CorrelationConstants.CORRELATION_ID_HEADER, correlationId);
            return Mono.empty();
        });

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    private static String firstNonBlank(String candidate, String fallback) {
        return StringUtils.hasText(candidate) ? candidate : fallback;
    }
}
