package com.paymentflow.agentic.provider.razorpay;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.paymentflow.agentic.config.AgenticProperties;
import com.paymentflow.agentic.config.RestClientConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * A thin HTTP adapter over the two Razorpay endpoints Depth 1 needs, and no others.
 *
 * <pre>
 *   POST /v1/orders               create an order for an amount
 *   GET  /v1/orders/{id}/payments what, if anything, a cardholder has authorized against it
 * </pre>
 *
 * <p><b>These two endpoints are the whole integration, and that is a consequence of a verified
 * fact rather than a shortcut.</b> Razorpay has no server-to-server "authorize this instrument
 * now" call: an order is created server-side, a cardholder authorizes it client-side through
 * Checkout, and the server then reads what happened. There is no third endpoint that would let
 * this service skip the middle step, so the adapter does not pretend there is.
 *
 * <p>The credential is HTTP Basic, applied as a header at the moment of the call. It is not on
 * any record, not in any log line, and not in any exception message — {@link RazorpayRequestException}
 * deliberately carries a code and a status and nothing else.
 *
 * <p>Resilience follows the platform hop's pattern and its own {@code razorpay} instances:
 * reads are retried, the order creation is not. Creating an order twice would leave two orders
 * for one payment, and while Razorpay would tolerate that, the reconciliation afterwards is
 * somebody's afternoon.
 */
@Component
public class RazorpayClient {

    private static final Logger log = LoggerFactory.getLogger(RazorpayClient.class);

    /** Shared with the retry and circuit-breaker instance names in {@code application.yaml}. */
    private static final String INSTANCE_NAME = "razorpay";

    private static final String ORDERS_PATH = "/v1/orders";
    private static final String ORDER_PAYMENTS_PATH = "/v1/orders/{orderId}/payments";

    /** Razorpay's error codes are plain uppercase identifiers; anything else is not repeated. */
    private static final Pattern WELL_FORMED_CODE = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");

    private final RestClient restClient;
    private final AgenticProperties properties;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public RazorpayClient(@Qualifier(RestClientConfig.RAZORPAY_CLIENT) RestClient restClient,
                          AgenticProperties properties,
                          CircuitBreakerRegistry circuitBreakerRegistry,
                          RetryRegistry retryRegistry) {
        this.restClient = restClient;
        this.properties = properties;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(INSTANCE_NAME);
        this.retry = retryRegistry.retry(INSTANCE_NAME);
    }

    // ── Wire shapes ─────────────────────────────────────────────────────────────────────

    /**
     * An order, as Razorpay returns it.
     *
     * @param status {@code created} until something is attempted, {@code attempted} once a
     *               cardholder has tried, {@code paid} once one has succeeded. Read, never
     *               trusted on its own — {@link #orderPayments} is what establishes an outcome
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RazorpayOrder(String id, String status, long amount, String currency,
                                @JsonProperty("amount_paid") long amountPaid) {
    }

    /**
     * One payment attempted against an order.
     *
     * @param status {@code authorized} or {@code captured} mean a cardholder authorized this.
     *               {@code failed} means one tried and was refused. {@code created} means an
     *               attempt began and did not finish, which is not an authorization
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RazorpayPayment(String id, String status, long amount, String currency,
                                  @JsonProperty("error_code") String errorCode,
                                  @JsonProperty("error_reason") String errorReason) {

        public boolean isAuthorized() {
            return "authorized".equals(status) || "captured".equals(status);
        }

        public boolean isFailed() {
            return "failed".equals(status);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RazorpayPaymentCollection(int count, List<RazorpayPayment> items) {
    }

    // ── Operations ──────────────────────────────────────────────────────────────────────

    /**
     * Creates an order for an amount. This is a real call with a real effect at Razorpay.
     *
     * <p>Not retried: an order is a created object, and a retry that succeeded after a timeout
     * whose first attempt also succeeded leaves two of them.
     *
     * @param amountMinor in the currency's minor unit, which for INR is paise — the same unit
     *                    this platform uses everywhere, so no conversion happens here or anywhere
     * @param receipt     the caller's own reference, carried back on the order for reconciliation
     */
    public RazorpayOrder createOrder(long amountMinor, String currency, String receipt,
                                     Map<String, String> notes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amountMinor);
        body.put("currency", currency);
        if (receipt != null && !receipt.isBlank()) {
            body.put("receipt", receipt);
        }
        if (notes != null && !notes.isEmpty()) {
            body.put("notes", notes);
        }

        return circuitBreaker.executeSupplier(() -> exchange(
                restClient.post()
                        .uri(ORDERS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .headers(this::authenticate)
                        .body(body),
                RazorpayOrder.class));
    }

    /**
     * What a cardholder has actually authorized against an order.
     *
     * <p>The only way this service learns whether a payment happened. A read, so it is retried.
     */
    public List<RazorpayPayment> orderPayments(String orderId) {
        RazorpayPaymentCollection collection = read(() -> exchange(
                restClient.get()
                        .uri(ORDER_PAYMENTS_PATH, orderId)
                        .headers(this::authenticate),
                RazorpayPaymentCollection.class));
        return collection == null || collection.items() == null ? List.of() : collection.items();
    }

    // ── Plumbing ────────────────────────────────────────────────────────────────────────

    private <T> T exchange(RestClient.RequestHeadersSpec<?> spec, Class<T> type) {
        try {
            return spec.exchange((request, response) -> {
                HttpStatusCode status = response.getStatusCode();
                if (status.is4xxClientError()) {
                    throw new RazorpayRequestException(status.value(), readErrorCode(response));
                }
                if (status.isError()) {
                    throw new RazorpayUnavailableException(
                            "Razorpay returned HTTP %d.".formatted(status.value()));
                }
                return response.bodyTo(type);
            }, false);
        } catch (ResourceAccessException e) {
            throw new RazorpayUnavailableException(
                    "Razorpay could not be reached: " + e.getMessage(), e);
        }
    }

    private <T> T read(Supplier<T> call) {
        return retry.executeSupplier(() -> circuitBreaker.executeSupplier(call));
    }

    /**
     * HTTP Basic, built at call time.
     *
     * <p>{@code setBasicAuth} does the encoding, so the credential is never assembled into a
     * string this class holds or could accidentally log.
     */
    private void authenticate(HttpHeaders headers) {
        AgenticProperties.Razorpay razorpay = properties.razorpay();
        headers.setBasicAuth(razorpay.keyId(), razorpay.keySecret());
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
    }

    /**
     * Razorpay's own error code, sanitised.
     *
     * <p>Only the code is read — never the description, which is free text from outside this
     * service. Anything not shaped like a code is dropped rather than repeated.
     */
    @SuppressWarnings("unchecked")
    private static String readErrorCode(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            Map<String, Object> body = response.bodyTo(Map.class);
            if (body != null && body.get("error") instanceof Map<?, ?> error
                    && error.get("code") instanceof String code
                    && WELL_FORMED_CODE.matcher(code).matches()) {
                return code.toUpperCase(Locale.ROOT);
            }
        } catch (RuntimeException e) {
            log.debug("A Razorpay error body could not be parsed.", e);
        }
        return null;
    }
}
