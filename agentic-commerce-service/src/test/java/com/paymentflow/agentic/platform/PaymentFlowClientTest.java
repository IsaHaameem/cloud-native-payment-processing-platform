package com.paymentflow.agentic.platform;

import com.paymentflow.agentic.config.AgenticProperties;
import com.paymentflow.agentic.error.AgenticErrorCode;
import com.paymentflow.agentic.error.AgenticException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The client's contract against a stubbed platform.
 *
 * <p>Two groups of assertions carry real weight. The first is that every call presents the
 * credentials and headers a real external integrator must present — bearer key, pinned
 * revision, correlation id, and an idempotency key on every mutation. The second is the
 * split between a verdict and an outage: a 4xx must not be retried and must not trip the
 * breaker, and a 5xx must do both.
 */
class PaymentFlowClientTest {

    private static final UUID PAYMENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final String CORRELATION_ID = "0192a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b";
    private static final String IDEMPOTENCY_KEY = "agt_" + "a".repeat(64);
    private static final String BASE_URI = "http://gateway.test";

    private MockRestServiceServer server;
    private PaymentFlowClient client;

    @BeforeEach
    void setUp() {
        build(properties("sk_test_agentfixturekey"));
    }

    private void build(AgenticProperties properties) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URI);
        this.server = MockRestServiceServer.bindTo(builder).build();
        this.client = new PaymentFlowClient(builder.build(), properties, circuitBreakers(), retries());
    }

    @Nested
    @DisplayName("acting as an external API consumer")
    class ExternalConsumer {

        @Test
        @DisplayName("a read presents the merchant key, the pinned revision and the correlation id")
        void readSendsCredentialsAndHeaders() {
            server.expect(requestTo(BASE_URI + "/v1/payments/" + PAYMENT_ID))
                    .andExpect(method(org.springframework.http.HttpMethod.GET))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sk_test_agentfixturekey"))
                    .andExpect(header("PaymentFlow-Version", "2026-08-01"))
                    .andExpect(header("X-Correlation-Id", CORRELATION_ID))
                    .andExpect(headerDoesNotExist("Idempotency-Key"))
                    .andRespond(withSuccess(paymentJson("authorized"), MediaType.APPLICATION_JSON));

            PlatformResponse<PaymentView> response = client.getPayment(CORRELATION_ID, PAYMENT_ID);

            assertThat(response.body().status()).isEqualTo("authorized");
            assertThat(response.body().amountMinor()).isEqualTo(250_000L);
            assertThat(response.httpStatus()).isEqualTo(200);
            server.verify();
        }

        @Test
        @DisplayName("every mutation carries the derived idempotency key")
        void mutationsSendTheIdempotencyKey() {
            server.expect(requestTo(BASE_URI + "/v1/payments/" + PAYMENT_ID + "/authorize"))
                    .andExpect(method(org.springframework.http.HttpMethod.POST))
                    .andExpect(header("Idempotency-Key", IDEMPOTENCY_KEY))
                    .andRespond(withSuccess(paymentJson("authorized"), MediaType.APPLICATION_JSON));

            client.authorizePayment(CORRELATION_ID, IDEMPOTENCY_KEY, PAYMENT_ID);

            server.verify();
        }

        @Test
        @DisplayName("a mutation without a derived key fails loudly here rather than at the platform")
        void mutationWithoutKeyIsRefusedLocally() {
            assertThatThrownBy(() -> client.authorizePayment(CORRELATION_ID, "  ", PAYMENT_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("derived idempotency key");

            server.verify();
        }

        @Test
        @DisplayName("the payment body carries the server-derived amount and nothing the model wrote")
        void createSendsTheDerivedAmount() {
            server.expect(requestTo(BASE_URI + "/v1/payments"))
                    .andExpect(method(org.springframework.http.HttpMethod.POST))
                    .andExpect(jsonPath("$.amountMinor").value(250_000))
                    .andExpect(jsonPath("$.currency").value("INR"))
                    .andExpect(jsonPath("$.paymentMethodToken").value("tok_visa_approved"))
                    .andExpect(jsonPath("$.metadata.agent_checkout_id").value("checkout-1"))
                    .andRespond(withSuccess(paymentJson("created"), MediaType.APPLICATION_JSON));

            client.createPayment(CORRELATION_ID, IDEMPOTENCY_KEY, 250_000L, "INR", "Order",
                    "tok_visa_approved", Map.of("agent_checkout_id", "checkout-1"));

            server.verify();
        }

        @Test
        @DisplayName("a refund omits the amount when refunding everything remaining")
        void refundOmitsAmountWhenFull() {
            server.expect(requestTo(BASE_URI + "/v1/payments/" + PAYMENT_ID + "/refund"))
                    .andExpect(content().string("{\"reason\":\"Customer returned the item\"}"))
                    .andRespond(withSuccess(paymentJson("refunded"), MediaType.APPLICATION_JSON));

            client.refundPayment(CORRELATION_ID, IDEMPOTENCY_KEY, PAYMENT_ID, null,
                    "Customer returned the item");

            server.verify();
        }

        @Test
        @DisplayName("the platform's request id is captured for the action log")
        void requestIdIsCaptured() {
            server.expect(requestTo(BASE_URI + "/v1/payments/" + PAYMENT_ID))
                    .andRespond(withSuccess(paymentJson("captured"), MediaType.APPLICATION_JSON)
                            .headers(responseHeaders()));

            PlatformResponse<PaymentView> response = client.getPayment(CORRELATION_ID, PAYMENT_ID);

            assertThat(response.requestId()).isEqualTo("req_abc123");
            assertThat(response.correlationId()).isEqualTo(CORRELATION_ID);
        }
    }

    @Nested
    @DisplayName("a verdict is not an outage")
    class VerdictVersusOutage {

        @Test
        @DisplayName("a 4xx carries the platform's own code through verbatim")
        void clientErrorCarriesThePlatformCode() {
            server.expect(requestTo(BASE_URI + "/v1/payments/" + PAYMENT_ID + "/capture"))
                    .andRespond(withStatus(HttpStatus.CONFLICT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("""
                                    {"code":"PAYMENT_NOT_CAPTURABLE",
                                     "message":"This payment cannot be captured in its current state.",
                                     "requestId":"req_xyz789"}"""));

            assertThatThrownBy(() -> client.capturePayment(CORRELATION_ID, IDEMPOTENCY_KEY, PAYMENT_ID))
                    .isInstanceOf(PaymentFlowClientException.class)
                    .satisfies(e -> {
                        PaymentFlowClientException failure = (PaymentFlowClientException) e;
                        assertThat(failure.platformCode()).isEqualTo("PAYMENT_NOT_CAPTURABLE");
                        assertThat(failure.httpStatus()).isEqualTo(409);
                        assertThat(failure.requestId()).isEqualTo("req_xyz789");
                        assertThat(failure.isRetryableAtSameKey()).isFalse();
                    });
        }

        @Test
        @DisplayName("a 4xx read is not retried — a verdict repeated is the same verdict")
        void clientErrorIsNotRetried() {
            server.expect(ExpectedCount.once(), requestTo(BASE_URI + "/v1/payments/" + PAYMENT_ID))
                    .andRespond(withStatus(HttpStatus.NOT_FOUND)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"code\":\"NOT_FOUND\",\"message\":\"No such payment.\"}"));

            assertThatThrownBy(() -> client.getPayment(CORRELATION_ID, PAYMENT_ID))
                    .isInstanceOf(PaymentFlowClientException.class);

            server.verify();
        }

        @Test
        @DisplayName("a 5xx read is retried, because that one is about the platform and not the request")
        void serverErrorIsRetriedOnReads() {
            server.expect(ExpectedCount.times(3), requestTo(BASE_URI + "/v1/payments/" + PAYMENT_ID))
                    .andRespond(withServerError());

            assertThatThrownBy(() -> client.getPayment(CORRELATION_ID, PAYMENT_ID))
                    .isInstanceOf(PaymentFlowUnavailableException.class);

            server.verify();
        }

        @Test
        @DisplayName("a 5xx mutation is attempted exactly once — retrying money is this class's job to refuse")
        void serverErrorIsNotRetriedOnMutations() {
            server.expect(ExpectedCount.once(), requestTo(BASE_URI + "/v1/payments"))
                    .andRespond(withServerError());

            assertThatThrownBy(() -> client.createPayment(CORRELATION_ID, IDEMPOTENCY_KEY, 250_000L, "INR",
                    null, null, Map.of()))
                    .isInstanceOf(PaymentFlowUnavailableException.class);

            server.verify();
        }

        @Test
        @DisplayName("an unparseable error body still produces a typed failure")
        void unparseableErrorBodyIsStillTyped() {
            server.expect(requestTo(BASE_URI + "/v1/payments/" + PAYMENT_ID + "/capture"))
                    .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                            .contentType(MediaType.TEXT_HTML)
                            .body("<html>a proxy wrote this</html>"));

            assertThatThrownBy(() -> client.capturePayment(CORRELATION_ID, IDEMPOTENCY_KEY, PAYMENT_ID))
                    .isInstanceOf(PaymentFlowClientException.class)
                    .satisfies(e -> assertThat(((PaymentFlowClientException) e).platformCode())
                            .isEqualTo("PLATFORM_ERROR"));
        }

        @Test
        @DisplayName("an in-flight duplicate is the one failure that may be sent again at the same key")
        void idempotencyConflictIsRetryableAtTheSameKey() {
            server.expect(requestTo(BASE_URI + "/v1/payments"))
                    .andRespond(withStatus(HttpStatus.CONFLICT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"code\":\"IDEMPOTENCY_CONFLICT\",\"message\":\"Still in flight.\"}"));

            assertThatThrownBy(() -> client.createPayment(CORRELATION_ID, IDEMPOTENCY_KEY, 1L, "INR", null,
                    null, Map.of()))
                    .satisfies(e -> assertThat(((PaymentFlowClientException) e).isRetryableAtSameKey())
                            .isTrue());
        }
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {

        @Test
        @DisplayName("a missing key is a configuration failure, named as one, with no request sent")
        void missingKeyIsAConfigurationFailure() {
            build(properties(""));

            assertThatThrownBy(() -> client.getPayment(CORRELATION_ID, PAYMENT_ID))
                    .isInstanceOf(AgenticException.class)
                    .satisfies(e -> assertThat(((AgenticException) e).agenticErrorCode())
                            .isEqualTo(AgenticErrorCode.PLATFORM_NOT_CONFIGURED))
                    .hasMessageContaining("paymentflow.agentic.platform.api-key");

            server.verify();
        }

        @Test
        @DisplayName("a rejected credential is never echoed back in the failure that reports it")
        void authenticationFailureDoesNotEchoTheKey() {
            String key = "sk_test_averyrealisticlookingsecretkey";
            build(properties(key));
            // The platform reflecting the offending credential back in its own message is the
            // worst case, and the one worth testing: this service must not then write it down.
            server.expect(requestTo(BASE_URI + "/v1/payments/" + PAYMENT_ID))
                    .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"code\":\"UNAUTHORIZED\",\"message\":\"The key " + key
                                    + " is not valid.\"}"));

            assertThatThrownBy(() -> client.getPayment(CORRELATION_ID, PAYMENT_ID))
                    .isInstanceOf(PaymentFlowClientException.class)
                    .satisfies(e -> {
                        assertThat(e.getMessage()).doesNotContain(key);
                        assertThat(e.getMessage()).contains("[REDACTED]");
                    });
        }
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────────────

    private static HttpHeaders responseHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Request-Id", "req_abc123");
        headers.set("X-Correlation-Id", CORRELATION_ID);
        return headers;
    }

    private static String paymentJson(String status) {
        return """
                {"object":"payment","id":"44444444-4444-4444-4444-444444444444",
                 "merchantId":"11111111-1111-1111-1111-111111111111",
                 "status":"%s","amountMinor":250000,"capturedAmountMinor":250000,
                 "refundedAmountMinor":0,"currency":"INR","mode":"test",
                 "description":"Order","paymentMethodToken":"tok_visa_approved",
                 "createdAt":"2026-08-22T10:00:00Z","updatedAt":"2026-08-22T10:00:01Z",
                 "metadata":{"agent_checkout_id":"checkout-1"}}""".formatted(status);
    }

    /** Mirrors {@code application.yaml}: a 4xx must never open the breaker. */
    private static CircuitBreakerRegistry circuitBreakers() {
        return CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .ignoreExceptions(PaymentFlowClientException.class)
                .build());
    }

    /** Mirrors {@code application.yaml}: three attempts, and a 4xx is never one of them. */
    private static RetryRegistry retries() {
        return RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(1))
                .ignoreExceptions(PaymentFlowClientException.class)
                .build());
    }

    private static AgenticProperties properties(String apiKey) {
        return new AgenticProperties(
                new AgenticProperties.Platform(BASE_URI, apiKey, 2000, 10000),
                new AgenticProperties.Policy("2026-08-20.1", "INR", 5_000_000L, 10_000_000L, 100_000L,
                        2_000_000L, 5_000_000L, 60, 30),
                new AgenticProperties.Checkout(30, 20),
                new AgenticProperties.Llm("anthropic", "https://example.invalid", "", "model", 2048, 0.2,
                        30000, 8, 120000, "", ""),
                new AgenticProperties.Razorpay(false, "https://example.invalid", "", "", 2000, 8000, "decline"),
                new AgenticProperties.Demo("", false));
    }
}
