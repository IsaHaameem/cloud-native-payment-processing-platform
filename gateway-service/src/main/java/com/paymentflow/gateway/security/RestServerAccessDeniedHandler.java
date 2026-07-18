package com.paymentflow.gateway.security;

import com.paymentflow.common.error.CommonErrorCode;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Returns a 403 {@code ApiError} when an authenticated caller lacks a required role. */
@Component
public class RestServerAccessDeniedHandler implements ServerAccessDeniedHandler {

    private final GatewayErrorResponseWriter errorWriter;

    public RestServerAccessDeniedHandler(GatewayErrorResponseWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException ex) {
        return errorWriter.write(exchange, CommonErrorCode.FORBIDDEN);
    }
}
