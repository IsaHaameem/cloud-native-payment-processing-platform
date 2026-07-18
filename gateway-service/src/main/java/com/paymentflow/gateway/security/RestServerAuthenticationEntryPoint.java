package com.paymentflow.gateway.security;

import com.paymentflow.common.error.CommonErrorCode;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Returns a 401 {@code ApiError} when authentication is missing or invalid. */
@Component
public class RestServerAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

    private final GatewayErrorResponseWriter errorWriter;

    public RestServerAuthenticationEntryPoint(GatewayErrorResponseWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        return errorWriter.write(exchange, CommonErrorCode.UNAUTHORIZED);
    }
}
