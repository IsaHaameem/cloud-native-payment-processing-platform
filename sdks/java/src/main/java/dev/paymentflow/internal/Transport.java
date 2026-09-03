package dev.paymentflow.internal;

import dev.paymentflow.ApiConnectionException;
import dev.paymentflow.ApiException;
import dev.paymentflow.PaymentFlowException;
import dev.paymentflow.PaymentFlowException.Detail;
import dev.paymentflow.RateLimitMeta;
import dev.paymentflow.RequestOptions;
import dev.paymentflow.ResponseMeta;
import dev.paymentflow.model.Operations.OperationDescriptor;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One HTTP attempt, wrapped in the retry loop that makes it safe (M26).
 *
 * <p><b>The one property this class exists to hold.</b> A retried mutation must reuse the
 * {@code Idempotency-Key} of the attempt it is retrying. The key is therefore generated
 * <em>once per logical call</em>, before the loop, and never inside it. §7.1 calls this the
 * SDK's single most important correctness property: a key regenerated per attempt turns "the
 * platform deduplicated your retry" into "you charged the customer twice", and it does so only
 * under the network conditions that make retries happen.
 *
 * <p><b>What is safe to retry.</b> Not "429 and 5xx". A response never arriving does not mean
 * the request never arrived, so this SDK retries only what it can replay safely: {@code GET} and
 * {@code DELETE}, which HTTP defines as idempotent, and any request carrying an
 * {@code Idempotency-Key}. {@code POST /v1/webhook_endpoints} is retried by neither rule.
 */
public final class Transport {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final Duration BASE_BACKOFF = Duration.ofMillis(500);
    private static final Duration MAX_BACKOFF = Duration.ofMillis(8_000);

    /**
     * The longest {@code Retry-After} this SDK waits out rather than surrender to. For
     * {@code DAILY_QUOTA_EXCEEDED} the header is the time until 00:00 UTC — up to 24 hours.
     * Sleeping that inside a caller's request handler is a hang, not "honouring the header".
     */
    private static final Duration MAX_HONOURED_RETRY_AFTER = Duration.ofSeconds(60);

    private final ClientConfig config;

    public Transport(ClientConfig config) {
        this.config = config;
    }

    /** A parsed response body ({@code Map}/{@code List}/scalar, or {@code null}) and its metadata. */
    public record Result(Object data, ResponseMeta meta) {}

    public Result request(RequestSpec spec) {
        RequestOptions options = spec.options() != null ? spec.options() : RequestOptions.NONE;
        OperationDescriptor op = spec.operation();

        URI uri = buildUri(spec);
        Map<String, String> headers = buildHeaders(op, options);
        String bodyJson = op.hasRequestBody() && spec.body() != null ? Json.write(spec.body()) : null;
        boolean replayable = op.replayable() || headers.containsKey(IDEMPOTENCY_HEADER);

        int maxRetries = options.maxRetries() != null ? options.maxRetries() : config.maxRetries();
        Duration timeout = options.timeout() != null ? options.timeout() : config.timeout();

        int attempt = 0;
        while (true) {
            attempt++;
            Attempt outcome = attempt(uri, op.method(), headers, bodyJson, timeout, attempt);
            if (outcome.success()) {
                return outcome.result();
            }

            int remaining = maxRetries - (attempt - 1);
            Duration delay = remaining > 0 && replayable && outcome.retryable()
                    ? retryDelay(outcome.retryAfterSeconds(), attempt)
                    : null;
            if (delay == null) {
                throw outcome.error();
            }
            sleep(delay);
        }
    }

    // ── One attempt ─────────────────────────────────────────────────────────────────────────

    private record Attempt(boolean success, Result result, boolean retryable, PaymentFlowException error,
                           Double retryAfterSeconds) {

        static Attempt ok(Result result) {
            return new Attempt(true, result, false, null, null);
        }

        static Attempt fail(boolean retryable, PaymentFlowException error, Double retryAfterSeconds) {
            return new Attempt(false, null, retryable, error, retryAfterSeconds);
        }
    }

    private Attempt attempt(URI uri, String method, Map<String, String> headers, String bodyJson,
                            Duration timeout, int attemptNumber) {
        HttpRequest.BodyPublisher publisher = bodyJson == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8);

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout).method(method, publisher);
        headers.forEach(builder::header);
        HttpRequest request = builder.build();

        HttpResponse<String> response;
        try {
            response = config.httpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            return Attempt.fail(true, new ApiConnectionException(
                    "The request timed out after " + timeout.toMillis() + "ms.",
                    new Detail(null, null, null, null, null, null, null, null, attemptNumber, null), e), null);
        } catch (IOException e) {
            return Attempt.fail(true, new ApiConnectionException(
                    "The request could not be completed: " + e.getMessage(),
                    new Detail(null, null, null, null, null, null, null, null, attemptNumber, null), e), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Attempt.fail(false, new ApiConnectionException(
                    "The request was interrupted.",
                    new Detail(null, null, null, null, null, null, null, null, attemptNumber, null), e), null);
        }

        return readResponse(response, attemptNumber);
    }

    private Attempt readResponse(HttpResponse<String> response, int attemptNumber) {
        int status = response.statusCode();
        String requestId = header(response, "X-Request-Id");
        String correlationId = header(response, "X-Correlation-Id");
        String apiVersion = header(response, "PaymentFlow-Version");
        Double retryAfterSeconds = doubleHeader(response, "Retry-After");

        ResponseMeta meta = new ResponseMeta(status, requestId, correlationId, apiVersion,
                header(response, "Deprecation") != null, rateLimitMeta(response), attemptNumber);

        String raw = response.body();
        boolean empty = status == 204 || raw == null || raw.isEmpty();
        Object body = null;
        boolean unreadable = false;
        if (!empty) {
            try {
                body = Json.parse(raw);
            } catch (JsonException e) {
                unreadable = true;
            }
        }

        boolean ok = status >= 200 && status < 300;
        if (ok) {
            if (unreadable) {
                return Attempt.fail(true, new ApiException(
                        "The API returned a success status with a body that is not JSON.",
                        new Detail(status, null, null, null, null, requestId, correlationId, null, attemptNumber, null),
                        null), null);
            }
            return Attempt.ok(new Result(body, meta));
        }

        boolean retryable = status == 429 || (status >= 500 && status != 501);
        PaymentFlowException error = Errors.fromResponse(
                unreadable ? null : body, status, requestId, correlationId, retryAfterSeconds, attemptNumber);
        return Attempt.fail(retryable, error, retryAfterSeconds);
    }

    // ── Building the request ────────────────────────────────────────────────────────────────

    private URI buildUri(RequestSpec spec) {
        OperationDescriptor op = spec.operation();
        StringBuilder path = new StringBuilder();
        int i = 0;
        String template = op.path();
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '{') {
                int end = template.indexOf('}', i);
                String name = template.substring(i + 1, end);
                String value = spec.pathParams().get(name);
                if (value == null || value.isEmpty()) {
                    throw new PaymentFlowException(
                            "`" + name + "` is required by " + op.id() + " and was not supplied.");
                }
                path.append(URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"));
                i = end + 1;
            } else {
                path.append(c);
                i++;
            }
        }

        StringBuilder queryString = new StringBuilder();
        for (Map.Entry<String, Object> entry : spec.query().entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            String name = entry.getKey();
            if (!op.queryParameters().contains(name)) {
                throw new PaymentFlowException("`" + name + "` is not a query parameter of " + op.id()
                        + ". It accepts: " + String.join(", ", op.queryParameters()) + ".");
            }
            if (value instanceof Iterable<?> iterable) {
                for (Object element : iterable) {
                    appendQuery(queryString, name, String.valueOf(element));
                }
            } else if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> nested : map.entrySet()) {
                    if (nested.getValue() == null) {
                        continue;
                    }
                    appendQuery(queryString, name + "[" + nested.getKey() + "]", String.valueOf(nested.getValue()));
                }
            } else {
                appendQuery(queryString, name, String.valueOf(value));
            }
        }

        String full = config.baseUrl() + path + (queryString.length() == 0 ? "" : "?" + queryString);
        return URI.create(full);
    }

    private static void appendQuery(StringBuilder out, String name, String value) {
        if (out.length() > 0) {
            out.append('&');
        }
        out.append(URLEncoder.encode(name, StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private Map<String, String> buildHeaders(OperationDescriptor op, RequestOptions options) {
        // LinkedHashMap so header order is stable in tests.
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + config.apiKey());
        headers.put("Accept", "application/json");
        headers.put("PaymentFlow-Version", config.apiVersion());
        headers.put("User-Agent", config.userAgent());
        if (op.hasRequestBody()) {
            headers.put("Content-Type", "application/json");
        }
        if (options.correlationId() != null) {
            headers.put("X-Correlation-Id", options.correlationId());
        }
        if (op.requiredHeaders().contains(IDEMPOTENCY_HEADER)) {
            headers.put(IDEMPOTENCY_HEADER,
                    options.idempotencyKey() != null ? options.idempotencyKey() : UUID.randomUUID().toString());
        } else if (options.idempotencyKey() != null) {
            // The caller asked for one on an operation the contract does not require it for. Sent
            // rather than dropped: they know something about their own retry story that the
            // contract does not.
            headers.put(IDEMPOTENCY_HEADER, options.idempotencyKey());
        }
        return headers;
    }

    // ── Backoff ─────────────────────────────────────────────────────────────────────────────

    private static Duration retryDelay(Double retryAfterSeconds, int attempt) {
        if (retryAfterSeconds != null) {
            Duration requested = Duration.ofMillis((long) (retryAfterSeconds * 1000));
            return requested.compareTo(MAX_HONOURED_RETRY_AFTER) > 0 ? null : requested;
        }
        // Full jitter: uniform over [0, ceiling). With several clients recovering from one outage,
        // ceiling/2 + jitter reconverges them into the wave that caused it; full jitter spreads it.
        long ceilingMillis = Math.min(MAX_BACKOFF.toMillis(), BASE_BACKOFF.toMillis() * (1L << (attempt - 1)));
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(ceilingMillis + 1));
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiConnectionException("The retry wait was interrupted.",
                    Detail.empty(), e);
        }
    }

    // ── Response headers ────────────────────────────────────────────────────────────────────

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElse(null);
    }

    private static Double doubleHeader(HttpResponse<?> response, String name) {
        String raw = header(response, name);
        if (raw == null) {
            return null;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long longHeader(HttpResponse<?> response, String name) {
        Double value = doubleHeader(response, name);
        return value == null ? null : value.longValue();
    }

    private static RateLimitMeta rateLimitMeta(HttpResponse<?> response) {
        Long limit = longHeader(response, "RateLimit-Limit");
        Long remaining = longHeader(response, "RateLimit-Remaining");
        Long reset = longHeader(response, "RateLimit-Reset");
        if (limit == null && remaining == null && reset == null) {
            return null;
        }
        return new RateLimitMeta(limit, remaining, reset);
    }
}
