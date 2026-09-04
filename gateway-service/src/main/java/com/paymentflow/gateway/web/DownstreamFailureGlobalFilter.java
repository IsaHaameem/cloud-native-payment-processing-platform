package com.paymentflow.gateway.web;

import com.paymentflow.common.error.CommonErrorCode;
import com.paymentflow.gateway.security.GatewayErrorResponseWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

/**
 * Turns "the service this route proxies to did not answer" into the platform's standard
 * {@code ApiError} 503, at the earliest point where that is still possible.
 *
 * <p><b>Why a {@link GlobalFilter} and not (only) the {@link GatewayErrorWebExceptionHandler}.</b>
 * The exception handler is the right place for "no route matched" and for anything thrown by a
 * {@code WebFilter}, but a downstream connection failure surfaces from
 * {@code NettyRoutingFilter} — the very last filter in the gateway chain — and by the time the
 * error has unwound back out to the {@code WebExceptionHandler} layer the earlier response
 * decorators (request-logging body capture, versioning) have already had the response handed to
 * them, and on some of those paths the response is committed with its default {@code 200} and no
 * body. An authenticated caller then sees a silent {@code 200 Content-Length: 0}, which is worse
 * than an error: it looks like an empty success. Catching the failure one step inside the chain,
 * wrapped directly around {@code NettyRoutingFilter}, means the response is untouched and a real
 * {@code 503} envelope can still be written.
 *
 * <p>Ordered just before {@code NettyRoutingFilter} ({@link Ordered#LOWEST_PRECEDENCE}) so its
 * {@code chain.filter(exchange)} call is the one that runs the routing filter and therefore the
 * one that observes its error.
 */
@Component
public class DownstreamFailureGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(DownstreamFailureGlobalFilter.class);

    private final GatewayErrorResponseWriter errorWriter;

    public DownstreamFailureGlobalFilter(GatewayErrorResponseWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 1;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange).onErrorResume(ex -> {
            if (!isDownstreamUnavailable(ex)) {
                return Mono.error(ex);
            }
            if (exchange.getResponse().isCommitted()) {
                // Nothing left to do but let it unwind — but say so, loudly, rather than
                // leaving a committed empty 200 look like a success in the logs too.
                log.error("Downstream unreachable for {} and the response was already committed",
                        exchange.getRequest().getPath(), ex);
                return Mono.error(ex);
            }
            log.error("Downstream service unreachable for {}", exchange.getRequest().getPath(), ex);
            return errorWriter.write(exchange, CommonErrorCode.SERVICE_UNAVAILABLE);
        });
    }

    /**
     * True when {@code ex} or one of its causes is a connect/timeout/DNS failure, or a
     * {@link ResponseStatusException} carrying a 502/503/504 (which is how
     * {@code NettyRoutingFilter} reports a response-timeout). Deliberately narrow: a 4xx from a
     * downstream is that service's own answer and must pass straight through.
     */
    static boolean isDownstreamUnavailable(Throwable ex) {
        for (Throwable t = ex; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof ConnectException
                    || t instanceof UnknownHostException
                    || t instanceof TimeoutException) {
                return true;
            }
            if (t instanceof ResponseStatusException rse) {
                int code = rse.getStatusCode().value();
                if (code == 502 || code == 503 || code == 504) {
                    return true;
                }
            }
            String name = t.getClass().getSimpleName();
            if (name.equals("ConnectTimeoutException")
                    || name.equals("PrematureCloseException")
                    || name.equals("AbortedException")
                    || name.equals("AnnotatedConnectException")
                    || name.equals("NativeConnectException")) {
                return true;
            }
        }
        return false;
    }
}
