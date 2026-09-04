package com.paymentflow.gateway.web;

import com.paymentflow.common.correlation.CorrelationConstants;
import com.paymentflow.gateway.security.GatewayErrorResponseWriter;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class DownstreamFailureGlobalFilterTest {

    private final DownstreamFailureGlobalFilter filter =
            new DownstreamFailureGlobalFilter(new GatewayErrorResponseWriter(new tools.jackson.databind.ObjectMapper()));

    @Test
    void classifiesConnectivityFailuresIncludingWrappedAndTimeoutCauses() {
        assertThat(DownstreamFailureGlobalFilter.isDownstreamUnavailable(new ConnectException("refused"))).isTrue();
        assertThat(DownstreamFailureGlobalFilter.isDownstreamUnavailable(
                new RuntimeException("boom", new IOException("io", new ConnectException("refused"))))).isTrue();
        assertThat(DownstreamFailureGlobalFilter.isDownstreamUnavailable(new UnknownHostException("no-dns"))).isTrue();
        assertThat(DownstreamFailureGlobalFilter.isDownstreamUnavailable(new TimeoutException("slow"))).isTrue();
        assertThat(DownstreamFailureGlobalFilter.isDownstreamUnavailable(
                new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "took too long"))).isTrue();
        assertThat(DownstreamFailureGlobalFilter.isDownstreamUnavailable(
                new ResponseStatusException(HttpStatus.BAD_GATEWAY, "bad upstream"))).isTrue();
    }

    @Test
    void doesNotClassifyOrdinaryErrorsOrDownstream4xxAsUnavailable() {
        assertThat(DownstreamFailureGlobalFilter.isDownstreamUnavailable(new IllegalStateException("bug"))).isFalse();
        assertThat(DownstreamFailureGlobalFilter.isDownstreamUnavailable(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "no such thing"))).isFalse();
        assertThat(DownstreamFailureGlobalFilter.isDownstreamUnavailable(
                new ResponseStatusException(HttpStatus.CONFLICT, "invalid"))).isFalse();
    }

    @Test
    void writesTheStandardApiError503WhenTheRouteChainErrorsWithAConnectFailure() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/balance")
                        .header(CorrelationConstants.CORRELATION_ID_HEADER, "corr-1")
                        .header(CorrelationConstants.REQUEST_ID_HEADER, "req-1"));

        StepVerifier.create(filter.filter(exchange, ex -> Mono.error(new ConnectException("Connection refused"))))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        String body = bodyAsString(exchange);
        assertThat(body).contains("\"code\":\"SERVICE_UNAVAILABLE\"");
        assertThat(body).contains("\"type\":\"api_error\"");
        assertThat(body).contains("\"correlationId\":\"corr-1\"");
        assertThat(body).contains("\"requestId\":\"req-1\"");
    }

    @Test
    void propagatesUnrelatedErrorsUnchanged() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/balance"));

        StepVerifier.create(filter.filter(exchange, ex -> Mono.error(new IllegalStateException("real bug"))))
                .verifyErrorSatisfies(t -> assertThat(t).isInstanceOf(IllegalStateException.class));
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    private static String bodyAsString(MockServerWebExchange exchange) {
        DataBuffer buffer = exchange.getResponse().getBody().blockFirst();
        assertThat(buffer).isNotNull();
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
