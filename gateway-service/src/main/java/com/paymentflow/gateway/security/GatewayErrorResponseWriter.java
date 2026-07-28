package com.paymentflow.gateway.security;

import com.paymentflow.common.correlation.CorrelationConstants;
import com.paymentflow.common.dto.error.ApiError;
import com.paymentflow.common.error.ApiErrorFactory;
import com.paymentflow.common.error.ErrorCode;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * Serializes the standard {@link ApiError} envelope onto a reactive response. Shared by
 * the authentication entry point, access-denied handler, and the catch-all exception
 * handler so the three write the exact same JSON shape one way, in one place.
 */
@Component
public class GatewayErrorResponseWriter {

    private final ObjectMapper objectMapper;

    public GatewayErrorResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Mono<Void> write(ServerWebExchange exchange, ErrorCode errorCode) {
        return write(exchange, errorCode, errorCode.defaultMessage());
    }

    /**
     * Assembly goes through {@link ApiErrorFactory}, shared with every service's
     * {@code GlobalExceptionHandler} (M21.4). This path answers requests that never reach a
     * service at all — an unauthenticated call, or one over its rate limit — so if it were
     * the one place that forgot `type`, `docUrl` or `requestId`, the fields would be missing
     * from exactly the errors an integrator hits first.
     */
    public Mono<Void> write(ServerWebExchange exchange, ErrorCode errorCode, String message) {
        HttpStatus status = HttpStatus.valueOf(errorCode.httpStatus());
        ApiError body = ApiErrorFactory.create(errorCode, message,
                exchange.getRequest().getPath().value(),
                exchange.getRequest().getHeaders().getFirst(CorrelationConstants.REQUEST_ID_HEADER),
                exchange.getRequest().getHeaders().getFirst(CorrelationConstants.CORRELATION_ID_HEADER));

        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        byte[] bytes = objectMapper.writeValueAsBytes(body);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
