package dev.paymentflow;

import dev.paymentflow.model.PaymentResponse;
import dev.paymentflow.resources.Payments;
import dev.paymentflow.resources.WebhookEndpoints;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportTest {

    private static final String PAYMENT = "{\"id\":\"pay_1\",\"amountMinor\":1000,\"currency\":\"USD\",\"status\":\"created\"}";

    @Test
    void createSendsTheContractHeadersAndBodyAndReturnsTheMappedObject() {
        FakeHttp http = new FakeHttp(FakeHttp.Turn.ok(201, PAYMENT));
        PaymentFlow client = FakeHttp.client(http);

        PaymentResponse payment = client.payments().create(
                Payments.params().amountMinor(1000).currency("USD").description("Order A-1234"));

        assertEquals("pay_1", payment.id());
        assertEquals(1000L, payment.amountMinor());

        HttpRequest sent = http.requests.get(0);
        assertEquals("POST", sent.method());
        assertTrue(sent.uri().toString().endsWith("/v1/payments"));
        assertEquals("Bearer sk_test_fake", FakeHttp.header(sent).apply("Authorization"));
        assertEquals("2026-08-01", FakeHttp.header(sent).apply("PaymentFlow-Version"));
        assertNotNull(FakeHttp.header(sent).apply("Idempotency-Key"));
        assertTrue(FakeHttp.header(sent).apply("User-Agent").startsWith("paymentflow-java/"));
    }

    @Test
    void aRetriedMutationReusesTheIdempotencyKeyOfTheAttemptItRetries() {
        FakeHttp http = new FakeHttp(
                FakeHttp.Turn.ok(503, "{\"type\":\"api_error\",\"message\":\"try later\"}", Map.of("Retry-After", "0")),
                FakeHttp.Turn.ok(201, PAYMENT));
        PaymentFlow client = FakeHttp.client(http);

        client.payments().create(Payments.params().amountMinor(1000).currency("USD"));

        assertEquals(2, http.calls());
        String first = http.requests.get(0).headers().firstValue("Idempotency-Key").orElseThrow();
        String second = http.requests.get(1).headers().firstValue("Idempotency-Key").orElseThrow();
        assertEquals(first, second, "the retry must carry the same Idempotency-Key");
    }

    @Test
    void aSuppliedIdempotencyKeyIsUsedAsGiven() {
        FakeHttp http = new FakeHttp(FakeHttp.Turn.ok(201, PAYMENT));
        PaymentFlow client = FakeHttp.client(http);

        client.payments().create(Payments.params().amountMinor(1000).currency("USD"),
                RequestOptions.builder().idempotencyKey("my-own-key").build());

        assertEquals("my-own-key", http.requests.get(0).headers().firstValue("Idempotency-Key").orElseThrow());
    }

    @Test
    void aNonReplayablePostIsNotRetried() {
        FakeHttp http = new FakeHttp(
                FakeHttp.Turn.ok(503, "{\"type\":\"api_error\",\"message\":\"nope\"}"),
                FakeHttp.Turn.ok(201, "{}"));
        PaymentFlow client = FakeHttp.client(http);

        assertThrows(ApiException.class, () -> client.webhookEndpoints().create(
                WebhookEndpoints.params().url("https://x.test/hook").enabledEvents(java.util.List.of("*"))));
        assertEquals(1, http.calls(), "createWebhookEndpoint carries no Idempotency-Key and must not be replayed");
    }

    @Test
    void a400IsAnInvalidRequestExceptionCarryingTheParam() {
        FakeHttp http = new FakeHttp(FakeHttp.Turn.ok(400,
                "{\"type\":\"invalid_request_error\",\"code\":\"VALIDATION_ERROR\",\"param\":\"currency\","
                        + "\"message\":\"currency is required\"}"));
        PaymentFlow client = FakeHttp.client(http);

        InvalidRequestException e = assertThrows(InvalidRequestException.class,
                () -> client.payments().create(Payments.params().amountMinor(1000).currency("USD")));
        assertEquals("currency", e.param());
        assertEquals("VALIDATION_ERROR", e.code());
        assertEquals(400, e.statusCode());
    }

    @Test
    void a401IsAnAuthenticationExceptionEvenWithNoBody() {
        FakeHttp http = new FakeHttp(FakeHttp.Turn.ok(401, ""));
        PaymentFlow client = FakeHttp.client(http);
        assertThrows(AuthenticationException.class, () -> client.payments().retrieve("pay_1"));
    }

    @Test
    void aLongRetryAfterIsNotWaitedOutButReportedOnTheException() {
        FakeHttp http = new FakeHttp(FakeHttp.Turn.ok(429,
                "{\"type\":\"rate_limit_error\",\"code\":\"DAILY_QUOTA_EXCEEDED\",\"message\":\"slow down\"}",
                Map.of("Retry-After", "86400")));
        PaymentFlow client = FakeHttp.client(http);

        RateLimitException e = assertThrows(RateLimitException.class, () -> client.payments().retrieve("pay_1"));
        assertEquals(86400.0, e.retryAfterSeconds());
        assertEquals(1, http.calls(), "a 24-hour Retry-After is not slept off inside a request handler");
    }

    @Test
    void aTimeoutIsRetriedThenSurfacedAsAConnectionError() {
        FakeHttp http = new FakeHttp(
                FakeHttp.Turn.ioError(new HttpTimeoutException("timed out")),
                FakeHttp.Turn.ok(200, PAYMENT));
        PaymentFlow client = FakeHttp.client(http);

        PaymentResponse payment = client.payments().retrieve("pay_1");
        assertEquals("pay_1", payment.id());
        assertEquals(2, http.calls());
    }

    @Test
    void a204LeavesTheVoidMethodsWithNothingToReturn() {
        FakeHttp http = new FakeHttp(FakeHttp.Turn.ok(204, ""));
        PaymentFlow client = FakeHttp.client(http);
        client.webhookEndpoints().delete("we_1"); // must not throw on an empty body
        assertEquals("DELETE", http.requests.get(0).method());
    }

    @Test
    void aListRequestPutsFiltersOnTheQueryString() {
        FakeHttp http = new FakeHttp(FakeHttp.Turn.ok(200, "{\"data\":[],\"hasMore\":false}"));
        PaymentFlow client = FakeHttp.client(http);
        client.payments().list(Payments.listParams().status("captured").limit(2), null);
        String query = http.requests.get(0).uri().getQuery();
        assertTrue(query.contains("status=captured"), query);
        assertTrue(query.contains("limit=2"), query);
    }
}
