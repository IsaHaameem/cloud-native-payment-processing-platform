package com.paymentflow.agentic.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * How this service reaches the payment platform: over HTTPS, through the gateway, with one
 * merchant API key. <b>Like any other integrator, and with no more authority than one.</b>
 *
 * <h2>What this class deliberately is not</h2>
 *
 * <ul>
 *   <li><b>It does not touch a database.</b> No payment, refund, ledger entry or audit row is
 *       ever read or written from here. The platform's schemas are not on this service's
 *       classpath and its datasource points at the {@code agentic} schema alone.</li>
 *   <li><b>It does not bypass the gateway.</b> Every call goes to the public {@code /v1}
 *       surface at the configured gateway base URI. There is no path to a service's internal
 *       port, and no internal-context signing here — this service <em>verifies</em> a signed
 *       context on its one inbound endpoint and signs nothing outbound.</li>
 *   <li><b>It does not call payment-service internals.</b> Every operation below appears in
 *       {@code docs/openapi.yaml}. If an operation is not in the published contract, this
 *       service cannot perform it.</li>
 * </ul>
 *
 * <p>The consequence is the security property the whole extension rests on: everything the
 * agent can do to money is bounded by what one merchant key with {@code payments:read} and
 * {@code payments:write} can do — its scopes, its merchant, its mode, and its rate limit — and
 * the platform enforces all four whatever this service believes.
 *
 * <h2>The pinned API revision</h2>
 *
 * <p>{@link #API_VERSION} is a literal, and it is <b>not</b> read from the platform's own
 * {@code ApiVersions.CURRENT}. Pinning is the whole point of a version header: an external
 * consumer that silently followed the platform's current revision would inherit every breaking
 * change on the day it shipped, which is precisely what pinning exists to prevent. It is
 * pinned to the revision that spells payment status in lowercase {@code snake_case}, which is
 * what {@link PaymentView} parses.
 *
 * <h2>Resilience, and what is deliberately not retried</h2>
 *
 * <p>Reads are wrapped in Retry → CircuitBreaker; <b>mutations are wrapped in the circuit
 * breaker only and are never retried by this class</b>. A money call is retried, if at all, by
 * re-running the same tool, which re-derives the same idempotency key and therefore meets the
 * platform's own replay record rather than creating a second payment. A retry loop here would
 * be a second, independent mechanism for repeating a charge, and the two could not be reasoned
 * about together.
 *
 * <p>Composed programmatically against the Spring-managed registries, following D49 and
 * {@code SandboxAuthorizationAdvisor}'s precedent: it sidesteps {@code spring-boot-starter-aop}
 * and any {@code @Order} aspect-ordering configuration while keeping the registries
 * Micrometer-bound. The {@code paymentFlow} time limiter declared in {@code application.yaml}
 * is <b>not</b> wired here: a time limiter needs an async boundary and a thread pool this
 * module does not have, and the socket-level connect and read timeouts on the injected
 * {@link RestClient} are the budget that actually enforces the deadline. Recorded rather than
 * quietly ignored.
 */
@Component
public class PaymentFlowClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentFlowClient.class);

    /** Shared with the retry and circuit-breaker instance names in {@code application.yaml}. */
    private static final String INSTANCE_NAME = "paymentFlow";

    /**
     * The API revision this service is written against. A literal by design — see the class
     * javadoc. Changing it is a decision to re-verify {@link PaymentView} against a different
     * wire shape, not a routine bump.
     */
    static final String API_VERSION = "2026-08-01";

    private static final String HEADER_IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String HEADER_CORRELATION_ID = "X-Correlation-Id";
    private static final String HEADER_REQUEST_ID = "X-Request-Id";
    private static final String HEADER_API_VERSION = "PaymentFlow-Version";

    private final RestClient restClient;
    private final AgenticProperties properties;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public PaymentFlowClient(@Qualifier(RestClientConfig.PLATFORM_CLIENT) RestClient restClient,
                             AgenticProperties properties,
                             CircuitBreakerRegistry circuitBreakerRegistry,
                             RetryRegistry retryRegistry) {
        this.restClient = restClient;
        this.properties = properties;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(INSTANCE_NAME);
        this.retry = retryRegistry.retry(INSTANCE_NAME);
    }

    // ── Reads ───────────────────────────────────────────────────────────────────────────

    /** {@code GET /v1/payments/{id}}. The only way this service ever learns a payment's state. */
    public PlatformResponse<PaymentView> getPayment(String correlationId, UUID paymentId) {
        return read(() -> exchange(
                restClient.get()
                        .uri("/v1/payments/{id}", paymentId)
                        .headers(headers -> commonHeaders(headers, correlationId)),
                PaymentView.class));
    }

    /**
     * {@code GET /v1/test/cards} — the platform's own catalogue of test instrument tokens.
     *
     * <p>This is the allow-list {@link com.paymentflow.agentic.tool.money.InstrumentAllowList}
     * checks {@code complete_checkout}'s {@code instrumentToken} against, which is what makes
     * AD-12's "never model-invented" enforceable rather than aspirational: the permitted set is
     * the platform's, not a list maintained here that could drift from it.
     *
     * <p>The one endpoint in the public API that takes no authentication, so it is reachable
     * even when the merchant key is misconfigured — but it is still sent with the pinned
     * revision, because the shape it returns is versioned like everything else.
     */
    public PlatformResponse<List<TestCardView>> listTestCards(String correlationId) {
        return read(() -> exchange(
                restClient.get()
                        .uri("/v1/test/cards")
                        .headers(headers -> {
                            headers.set(HEADER_API_VERSION, API_VERSION);
                            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                            if (correlationId != null && !correlationId.isBlank()) {
                                headers.set(HEADER_CORRELATION_ID, correlationId);
                            }
                        }),
                TestCardView[].class))
                .map(List::of);
    }

    // ── Mutations ───────────────────────────────────────────────────────────────────────

    /**
     * {@code POST /v1/payments}.
     *
     * <p>{@code amountMinor} arrives from the checkout's own derived total. There is no
     * overload of this method that takes an amount from anywhere else.
     */
    public PlatformResponse<PaymentView> createPayment(String correlationId, String idempotencyKey,
                                                       long amountMinor, String currency, String description,
                                                       String paymentMethodToken, Map<String, String> metadata) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amountMinor", amountMinor);
        body.put("currency", currency);
        if (description != null && !description.isBlank()) {
            body.put("description", description);
        }
        if (paymentMethodToken != null && !paymentMethodToken.isBlank()) {
            body.put("paymentMethodToken", paymentMethodToken);
        }
        if (metadata != null && !metadata.isEmpty()) {
            body.put("metadata", metadata);
        }

        return mutate(() -> exchange(
                restClient.post()
                        .uri("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .headers(headers -> mutatingHeaders(headers, correlationId, idempotencyKey))
                        .body(body),
                PaymentView.class));
    }

    /** {@code POST /v1/payments/{id}/authorize}. No request body — the payment already carries its amount. */
    public PlatformResponse<PaymentView> authorizePayment(String correlationId, String idempotencyKey,
                                                          UUID paymentId) {
        return mutate(() -> exchange(
                restClient.post()
                        .uri("/v1/payments/{id}/authorize", paymentId)
                        .headers(headers -> mutatingHeaders(headers, correlationId, idempotencyKey)),
                PaymentView.class));
    }

    /** {@code POST /v1/payments/{id}/capture}. Captures the full authorized amount. */
    public PlatformResponse<PaymentView> capturePayment(String correlationId, String idempotencyKey,
                                                        UUID paymentId) {
        return mutate(() -> exchange(
                restClient.post()
                        .uri("/v1/payments/{id}/capture", paymentId)
                        .headers(headers -> mutatingHeaders(headers, correlationId, idempotencyKey)),
                PaymentView.class));
    }

    /**
     * {@code POST /v1/payments/{id}/refund}, returning the <em>payment</em> with its updated
     * refunded amount — which is what the published contract says this operation returns.
     *
     * <p>The platform bounds the refund at {@code captured − refunded} inside the payment
     * aggregate itself, before any row is written. The policy engine's refund caps sit on top
     * of that; neither replaces the other.
     */
    public PlatformResponse<PaymentView> refundPayment(String correlationId, String idempotencyKey,
                                                       UUID paymentId, Long amountMinor, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (amountMinor != null) {
            body.put("amountMinor", amountMinor);
        }
        if (reason != null && !reason.isBlank()) {
            body.put("reason", reason);
        }

        return mutate(() -> exchange(
                restClient.post()
                        .uri("/v1/payments/{id}/refund", paymentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .headers(headers -> mutatingHeaders(headers, correlationId, idempotencyKey))
                        .body(body),
                PaymentView.class));
    }

    // ── Plumbing ────────────────────────────────────────────────────────────────────────

    /**
     * The one place the request is actually made, and the one place a response becomes either
     * a value or a typed exception.
     *
     * <p>Uses {@code exchange} rather than {@code retrieve} so the status, the headers and the
     * body are all in scope at once: a step row needs the HTTP status and the platform's
     * request id, and neither is on the payload.
     */
    private <T> PlatformResponse<T> exchange(RestClient.RequestHeadersSpec<?> spec, Class<T> type) {
        try {
            return spec.exchange((request, response) -> {
                HttpStatusCode status = response.getStatusCode();
                String requestId = response.getHeaders().getFirst(HEADER_REQUEST_ID);
                String correlationId = response.getHeaders().getFirst(HEADER_CORRELATION_ID);

                if (status.isError()) {
                    throw toException(status.value(), readError(response), requestId, correlationId);
                }
                return new PlatformResponse<>(response.bodyTo(type), status.value(), requestId, correlationId);
            }, false);
        } catch (ResourceAccessException e) {
            // A connect failure, a socket timeout, or a read that never completed. Whether the
            // far side executed anything is unknowable from here, and the derived idempotency
            // key is what makes that acceptable rather than something to guess about.
            throw new PaymentFlowUnavailableException(
                    "The payment platform could not be reached: " + e.getMessage(), e);
        }
    }

    /** Reads are retried; the retry registry ignores {@link PaymentFlowClientException} by configuration. */
    private <T> PlatformResponse<T> read(Supplier<PlatformResponse<T>> call) {
        return retry.executeSupplier(() -> circuitBreaker.executeSupplier(call));
    }

    /** Mutations get the breaker and nothing else. Re-running the tool is the only retry there is. */
    private <T> PlatformResponse<T> mutate(Supplier<PlatformResponse<T>> call) {
        return circuitBreaker.executeSupplier(call);
    }

    private void requireConfigured() {
        if (!properties.platform().isConfigured()) {
            throw PaymentFlowUnavailableException.notConfigured();
        }
    }

    /**
     * Applied while the request is still being assembled, which is deliberate: the credential
     * check happens here, eagerly, <em>before</em> the resilience chain is entered and before
     * any header is built. A missing key is a configuration fault, and letting one count
     * toward the circuit breaker would take the agent offline for a reason no retry could ever
     * fix.
     */
    private void commonHeaders(HttpHeaders headers, String correlationId) {
        requireConfigured();
        headers.setBearerAuth(properties.platform().apiKey());
        headers.set(HEADER_API_VERSION, API_VERSION);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (correlationId != null && !correlationId.isBlank()) {
            headers.set(HEADER_CORRELATION_ID, correlationId);
        }
    }

    private void mutatingHeaders(HttpHeaders headers, String correlationId, String idempotencyKey) {
        commonHeaders(headers, correlationId);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            // Refused here rather than sent. The platform would reject it anyway, but a
            // mutating call leaving this service without a derived key means the derivation
            // was skipped — and that is a defect worth failing loudly on, not a 400 to relay.
            throw new IllegalStateException(
                    "A mutating platform call was attempted without a derived idempotency key.");
        }
        headers.set(HEADER_IDEMPOTENCY_KEY, idempotencyKey);
    }

    /**
     * The platform's error envelope, as far as this service needs it.
     *
     * <p>Deliberately tolerant: an error response that cannot be parsed must still produce a
     * typed failure rather than a {@code NullPointerException} three frames later, because the
     * case where a peer returns something unexpected is exactly the case where clear reporting
     * matters most.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ApiErrorBody(String code, String message, String type, String requestId, String correlationId) {
    }

    private static ApiErrorBody readError(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            ApiErrorBody body = response.bodyTo(ApiErrorBody.class);
            return body == null ? new ApiErrorBody(null, null, null, null, null) : body;
        } catch (RuntimeException e) {
            log.debug("The payment platform returned an error whose body could not be parsed.", e);
            return new ApiErrorBody(null, null, null, null, null);
        }
    }

    /**
     * Splits the platform's failures into the two kinds that must be treated differently: a
     * verdict about this request, and a statement about the platform.
     */
    private static RuntimeException toException(int status, ApiErrorBody error, String requestId,
                                                String correlationId) {
        String resolvedRequestId = requestId != null ? requestId : error.requestId();
        String resolvedCorrelationId = correlationId != null ? correlationId : error.correlationId();

        if (status >= 500) {
            return new PaymentFlowUnavailableException(status,
                    "The payment platform returned %d%s.".formatted(status,
                            resolvedRequestId == null ? "" : " (request " + resolvedRequestId + ")"));
        }
        PlatformErrorCode code = PlatformErrorCode.of(error.code(), status, error.message());
        return new PaymentFlowClientException(code,
                error.message() == null ? code.defaultMessage() : error.message(),
                resolvedRequestId, resolvedCorrelationId);
    }
}
