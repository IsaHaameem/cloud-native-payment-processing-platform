package com.paymentflow.gateway.filter;

import com.paymentflow.common.correlation.CorrelationConstants;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdWebFilterTest {

    private final CorrelationIdWebFilter filter = new CorrelationIdWebFilter();

    @Test
    void generatesIdsWhenAbsentAndEchoesCorrelationIdOnResponse() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/auth/ping"));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchange, (ex) -> {
            forwarded.set(ex);
            return ex.getResponse().setComplete();
        }).block();

        HttpHeaders downstreamHeaders = forwarded.get().getRequest().getHeaders();
        String correlationId = downstreamHeaders.getFirst(CorrelationConstants.CORRELATION_ID_HEADER);
        assertThat(correlationId).isNotBlank();
        assertThat(downstreamHeaders.getFirst(CorrelationConstants.REQUEST_ID_HEADER)).isNotBlank();
        assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationConstants.CORRELATION_ID_HEADER))
                .isEqualTo(correlationId);
    }

    @Test
    void preservesInboundCorrelationAndRequestIdsWhenPresent() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/auth/ping")
                        .header(CorrelationConstants.CORRELATION_ID_HEADER, "caller-correlation-id")
                        .header(CorrelationConstants.REQUEST_ID_HEADER, "caller-request-id"));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchange, (ex) -> {
            forwarded.set(ex);
            return ex.getResponse().setComplete();
        }).block();

        HttpHeaders downstreamHeaders = forwarded.get().getRequest().getHeaders();
        assertThat(downstreamHeaders.getFirst(CorrelationConstants.CORRELATION_ID_HEADER)).isEqualTo("caller-correlation-id");
        assertThat(downstreamHeaders.getFirst(CorrelationConstants.REQUEST_ID_HEADER)).isEqualTo("caller-request-id");
        assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationConstants.CORRELATION_ID_HEADER))
                .isEqualTo("caller-correlation-id");
    }
}
