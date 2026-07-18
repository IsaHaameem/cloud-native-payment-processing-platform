package com.paymentflow.gateway.web;

import com.paymentflow.common.error.CommonErrorCode;
import com.paymentflow.gateway.security.GatewayErrorResponseWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

import java.net.ConnectException;

/**
 * Reactive equivalent of common-lib's servlet-only {@code GlobalExceptionHandler} (which
 * stays inactive here — see common-lib D11): the catch-all for everything that is not a
 * Spring Security authentication/authorization failure (those go through the entry point
 * and access-denied handler instead) — no route matched, and downstream connectivity
 * failures — mapped onto the standard {@code ApiError} envelope.
 *
 * <p>Registered ahead of Boot's {@code DefaultErrorWebExceptionHandler} (order -1).
 */
@Component
@Order(-2)
public class GatewayErrorWebExceptionHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorWebExceptionHandler.class);

    private final GatewayErrorResponseWriter errorWriter;

    public GatewayErrorWebExceptionHandler(GatewayErrorResponseWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }

        if (ex instanceof ResponseStatusException rse && rse.getStatusCode().value() == 404) {
            log.warn("No route matched {}", exchange.getRequest().getPath());
            return errorWriter.write(exchange, CommonErrorCode.NOT_FOUND);
        }
        if (ex instanceof ConnectException) {
            log.error("Downstream service unreachable for {}", exchange.getRequest().getPath(), ex);
            return errorWriter.write(exchange, CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        if (ex instanceof ResponseStatusException rse) {
            return errorWriter.write(exchange, HttpStatus.valueOf(rse.getStatusCode().value()),
                    CommonErrorCode.BAD_REQUEST.code(), rse.getReason() != null ? rse.getReason()
                            : CommonErrorCode.BAD_REQUEST.defaultMessage());
        }

        log.error("Unhandled exception at {}", exchange.getRequest().getPath(), ex);
        return errorWriter.write(exchange, CommonErrorCode.INTERNAL_ERROR);
    }
}
