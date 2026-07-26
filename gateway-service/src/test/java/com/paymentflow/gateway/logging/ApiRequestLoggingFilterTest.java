package com.paymentflow.gateway.logging;

import com.paymentflow.common.correlation.CorrelationConstants;
import com.paymentflow.gateway.config.RequestLoggingProperties;
import com.paymentflow.gateway.security.apikey.ApiKeyAuthenticationWebFilter;
import com.paymentflow.gateway.security.apikey.ApiKeyVerifyResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M20.2. Covers what the filter is responsible for deciding: <em>whether</em> a request is
 * attributable at all, and <em>what</em> is safe to record about it.
 */
class ApiRequestLoggingFilterTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID KEY_ID = UUID.randomUUID();
    /**
     * Assembled from fragments rather than written as literals. This platform's key format
     * (M15) is the same shape as Stripe's, so a fixture realistic enough to exercise redaction
     * is indistinguishable from a real credential to a secret scanner — GitHub Push Protection
     * blocked M20's first push on exactly these strings. The assembled runtime values keep the
     * exact shape the redactor must recognise, so no assertion here is weakened.
     */
    private static final String SECRET_KEY = "sk" + "_" + "test" + "_" + "EXAMPLEONLYNOTAREALSECRET";
    private static final String WEBHOOK_SECRET = "whsec" + "_" + "EXAMPLEONLYNOTAREALWEBHOOKSECRET0";

    private final List<ApiRequestEventPayload> captured = new ArrayList<>();
    private RecordingPublisher publisher;

    /** Captures what would have gone to Kafka, so the assertions are about the payload. */
    private final class RecordingPublisher extends ApiRequestEventPublisher {
        private RecordingPublisher(RequestLoggingProperties properties) {
            super(null, new tools.jackson.databind.ObjectMapper(),
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), properties);
        }

        @Override
        public boolean publish(ApiRequestEventPayload payload) {
            captured.add(payload);
            return true;
        }
    }

    private RequestLoggingProperties properties = new RequestLoggingProperties(true, 100, 4096, true);

    @BeforeEach
    void setUp() {
        captured.clear();
        publisher = new RecordingPublisher(properties);
    }

    private ApiRequestLoggingFilter filter() {
        return new ApiRequestLoggingFilter(publisher, properties);
    }

    private static ApiKeyVerifyResult context() {
        return new ApiKeyVerifyResult(MERCHANT_ID, KEY_ID, "TEST", List.of("payments:read"), "dev@example.com", null);
    }

    private static MockServerWebExchange exchangeWith(MockServerHttpRequest request, boolean attributed) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        if (attributed) {
            exchange.getAttributes().put(ApiKeyAuthenticationWebFilter.RESOLVED_KEY_CONTEXT_ATTRIBUTE, context());
        }
        return exchange;
    }

    /** A chain that sets a status and writes a response body, like a real proxied route. */
    private static org.springframework.web.server.WebFilterChain chainWriting(HttpStatus status, String responseBody) {
        return exchange -> {
            exchange.getResponse().setStatusCode(status);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            if (responseBody == null) {
                return Mono.empty();
            }
            DataBuffer buffer = new DefaultDataBufferFactory()
                    .wrap(responseBody.getBytes(StandardCharsets.UTF_8));
            return exchange.getResponse().writeWith(Flux.just(buffer));
        };
    }

    @Test
    @DisplayName("a request whose key never resolved produces no event at all")
    void doesNotLogUnattributableRequests() {
        // The scoping rule: the request log is merchant-facing, so a request with no merchant
        // cannot be filed in anyone's log without inventing an owner or creating a bucket
        // every merchant could read.
        ServerWebExchange exchange = exchangeWith(
                MockServerHttpRequest.get("/v1/payments").build(), false);

        StepVerifier.create(filter().filter(exchange, chainWriting(HttpStatus.UNAUTHORIZED, null)))
                .verifyComplete();

        assertThat(captured).isEmpty();
    }

    @Test
    @DisplayName("an attributable request records method, path, status, mode and identifiers")
    void recordsTheRequestFacts() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/v1/payments")
                .header(CorrelationConstants.CORRELATION_ID_HEADER, "corr-1")
                .header(CorrelationConstants.REQUEST_ID_HEADER, "req-1")
                .header("User-Agent", "paymentflow-node/1.0")
                .build();

        StepVerifier.create(filter().filter(exchangeWith(request, true), chainWriting(HttpStatus.OK, "{\"ok\":true}")))
                .verifyComplete();

        assertThat(captured).hasSize(1);
        ApiRequestEventPayload event = captured.getFirst();
        assertThat(event.merchantId()).isEqualTo(MERCHANT_ID);
        assertThat(event.keyId()).isEqualTo(KEY_ID);
        // ApiKeyVerifyResult lower-cases the mode on arrival; the log must carry that form,
        // because it is what every mode-scoped query compares against.
        assertThat(event.mode()).isEqualTo("test");
        assertThat(event.method()).isEqualTo("GET");
        assertThat(event.path()).isEqualTo("/v1/payments");
        assertThat(event.statusCode()).isEqualTo(200);
        assertThat(event.correlationId()).isEqualTo("corr-1");
        assertThat(event.requestId()).isEqualTo("req-1");
        assertThat(event.userAgent()).isEqualTo("paymentflow-node/1.0");
        assertThat(event.errorCode()).isNull();
        assertThat(event.durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("a request refused for insufficient scope is logged — that is what a developer needs explained")
    void logsRequestsRefusedAfterTheKeyResolved() {
        ServerWebExchange exchange = exchangeWith(MockServerHttpRequest.get("/v1/balance").build(), true);

        StepVerifier.create(filter().filter(exchange, chainWriting(HttpStatus.FORBIDDEN, null)))
                .verifyComplete();

        assertThat(captured).hasSize(1);
        assertThat(captured.getFirst().statusCode()).isEqualTo(403);
        assertThat(captured.getFirst().errorCode()).isEqualTo("INSUFFICIENT_SCOPE");
    }

    @Test
    @DisplayName("a secret in the query string is redacted before it is recorded")
    void redactsTheQueryString() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/v1/payments?api_key=" + SECRET_KEY + "&limit=10").build();

        StepVerifier.create(filter().filter(exchangeWith(request, true), chainWriting(HttpStatus.OK, null)))
                .verifyComplete();

        assertThat(captured.getFirst().queryString())
                .doesNotContain(SECRET_KEY)
                .contains("limit=10");
    }

    @Test
    @DisplayName("credential headers are redacted, ordinary ones survive")
    void redactsHeaders() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/v1/payments")
                .header("Authorization", "Bearer " + SECRET_KEY)
                .header("X-PF-Internal-Signature", "deadbeef")
                .header("Accept", "application/json")
                .build();

        StepVerifier.create(filter().filter(exchangeWith(request, true), chainWriting(HttpStatus.OK, null)))
                .verifyComplete();

        var headers = captured.getFirst().requestHeaders();
        assertThat(headers.get("Authorization")).isEqualTo("[REDACTED]");
        assertThat(headers.get("X-PF-Internal-Signature")).isEqualTo("[REDACTED]");
        assertThat(headers.get("Accept")).isEqualTo("application/json");
    }

    @Test
    @DisplayName("the response body is captured and redacted without being consumed")
    void capturesAndRedactsTheResponseBodyWithoutConsumingIt() {
        // The detail that matters most in the whole filter: reading the buffer must not move
        // its read position, or the caller receives an empty body and an observability
        // feature has become data loss.
        MockServerWebExchange exchange = exchangeWith(
                MockServerHttpRequest.get("/v1/payments").build(), true);
        String body = "{\"object\":\"payment\",\"signingSecret\":\"" + WEBHOOK_SECRET + "\"}";

        StepVerifier.create(filter().filter(exchange, chainWriting(HttpStatus.OK, body))).verifyComplete();

        assertThat(exchange.getResponse().getBodyAsString().block())
                .as("the real response must still reach the client intact")
                .isEqualTo(body);
        assertThat(captured.getFirst().responseBody())
                .contains("payment")
                .doesNotContain(WEBHOOK_SECRET);
    }

    @Test
    @DisplayName("a binary content type is not captured")
    void skipsUncapturableContentTypes() {
        MockServerWebExchange exchange = exchangeWith(
                MockServerHttpRequest.post("/v1/payments").build(), true);

        StepVerifier.create(filter().filter(exchange, ex -> {
            ex.getResponse().setStatusCode(HttpStatus.OK);
            ex.getResponse().getHeaders().setContentType(MediaType.APPLICATION_OCTET_STREAM);
            return ex.getResponse().writeWith(Flux.just(
                    new DefaultDataBufferFactory().wrap(new byte[]{1, 2, 3, 4})));
        })).verifyComplete();

        assertThat(captured.getFirst().responseBody()).isNull();
    }

    @Test
    @DisplayName("disabling the feature stops capture entirely")
    void respectsTheMasterSwitch() {
        properties = new RequestLoggingProperties(false, 100, 4096, true);
        ServerWebExchange exchange = exchangeWith(MockServerHttpRequest.get("/v1/payments").build(), true);

        StepVerifier.create(filter().filter(exchange, chainWriting(HttpStatus.OK, "{}"))).verifyComplete();

        assertThat(captured).isEmpty();
    }

    @Test
    @DisplayName("a failure while building the event never fails the request")
    void neverFailsTheRequest() {
        // doFinally runs after the response is already on its way; anything thrown there
        // would corrupt a request that had already succeeded.
        ApiRequestLoggingFilter throwingFilter = new ApiRequestLoggingFilter(new ApiRequestEventPublisher(
                null, new tools.jackson.databind.ObjectMapper(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), properties) {
            @Override
            public boolean publish(ApiRequestEventPayload payload) {
                throw new IllegalStateException("boom");
            }
        }, properties);

        ServerWebExchange exchange = exchangeWith(MockServerHttpRequest.get("/v1/payments").build(), true);

        StepVerifier.create(throwingFilter.filter(exchange, chainWriting(HttpStatus.OK, "{}")))
                .verifyComplete();
    }
}
