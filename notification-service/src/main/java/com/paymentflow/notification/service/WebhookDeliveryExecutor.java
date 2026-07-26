package com.paymentflow.notification.service;

import com.paymentflow.notification.config.WebhookProperties;
import com.paymentflow.notification.crypto.WebhookSecretCipher;
import com.paymentflow.notification.domain.AttemptOutcome;
import com.paymentflow.notification.domain.WebhookDelivery;
import com.paymentflow.notification.domain.WebhookDeliveryAttempt;
import com.paymentflow.notification.domain.WebhookEndpoint;
import com.paymentflow.notification.domain.WebhookEvent;
import com.paymentflow.notification.egress.EgressDecision;
import com.paymentflow.notification.egress.EgressPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Makes the actual outbound HTTP call for one delivery attempt (M18.6) and records
 * exactly what happened as a {@link WebhookDeliveryAttempt}. Never throws to its caller:
 * every outcome — success, non-2xx, transport failure, egress refusal — becomes a
 * recorded attempt, because the delivery log's whole purpose is to answer "why did my
 * webhook not arrive?" and an unrecorded failure answers nothing.
 *
 * <p>Hardening, each item deliberate:
 * <ul>
 *   <li><b>The egress guard runs before every connect</b> (M18.5), and the request is
 *       made against the URL only after its resolved addresses were checked. A refusal is
 *       recorded as {@code BLOCKED}, never conflated with a connection error.</li>
 *   <li><b>Redirects are never followed.</b> {@code HttpClient.Redirect.NEVER}. A
 *       302 to {@code 169.254.169.254} is the standard way to walk straight through an
 *       egress check that only validated the original URL — following redirects would
 *       make M18.5 decorative.</li>
 *   <li><b>The response body is read through a bounded stream</b> and truncated at
 *       {@code maxResponseBytes}. A hostile endpoint streaming gigabytes must not be able
 *       to exhaust memory or fill the attempts table through us. {@code BodyHandlers
 *       .ofString()} would buffer the whole thing before we could refuse it, which is why
 *       this reads the stream itself.</li>
 *   <li><b>Per-attempt connect and request timeouts</b>, so one slow endpoint occupies a
 *       worker for a bounded time rather than indefinitely.</li>
 *   <li><b>The signature is computed over the exact bytes sent</b>, and during a rotation
 *       window over both the current and the superseded secret, so a receiver mid-roll
 *       verifies with whichever it holds.</li>
 * </ul>
 *
 * <p>Uses the JDK {@link HttpClient} rather than the {@code RestClient} V1's delivery path
 * uses: redirect policy, per-request timeouts, and streaming the response under a cap are
 * all first-class here and awkward or unavailable through {@code RestClient}'s
 * abstraction. V1's {@code WebhookDeliveryService} is left untouched and is retired by the
 * cutover, not modified.
 */
@Service
public class WebhookDeliveryExecutor {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryExecutor.class);
    private static final String USER_AGENT = "PaymentFlow-Webhooks/1.0";
    private static final int MAX_RECORDED_HEADERS = 20;

    private final EgressPolicy egressPolicy;
    private final WebhookSigner signer;
    private final WebhookSecretCipher secretCipher;
    private final WebhookEventFactory eventFactory;
    private final WebhookProperties properties;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final HttpClient httpClient;

    public WebhookDeliveryExecutor(EgressPolicy egressPolicy, WebhookSigner signer, WebhookSecretCipher secretCipher,
                                   WebhookEventFactory eventFactory, WebhookProperties properties,
                                   ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.egressPolicy = egressPolicy;
        this.signer = signer;
        this.secretCipher = secretCipher;
        this.eventFactory = eventFactory;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                // A 302 to a private address is the standard way to walk through an egress
                // check that only validated the original URL.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Performs one attempt and returns the recorded result. Rendering and signing happen
     * per attempt, not once per delivery: a retry carries a fresh timestamp (and therefore
     * a fresh signature), which is what keeps the receiver's replay window meaningful
     * across the ~24-hour retry schedule.
     */
    public WebhookDeliveryAttempt attempt(WebhookDelivery delivery, WebhookEvent event, WebhookEndpoint endpoint,
                                          int attemptNumber) {
        String url = endpoint.getUrl();
        String body = eventFactory.serialize(event);
        Instant signedAt = Instant.now();
        Map<String, String> requestHeaders = headersFor(event, endpoint, body, signedAt);
        String serializedRequestHeaders = objectMapper.writeValueAsString(requestHeaders);

        EgressDecision egress = egressPolicy.check(url);
        if (!egress.allowed()) {
            log.warn("Blocked webhook delivery {} to endpoint {}: {}",
                    delivery.getId(), endpoint.getId(), egress.reason());
            meterRegistry.counter("webhook_delivery_attempts_total", "outcome", "blocked").increment();
            return WebhookDeliveryAttempt.blocked(delivery.getId(), attemptNumber, url, serializedRequestHeaders,
                    body, egress.reason());
        }

        long startedAt = System.nanoTime();
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(properties.readTimeout())
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            requestHeaders.forEach(request::header);

            HttpResponse<InputStream> response =
                    httpClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            String responseBody = readCapped(response.body());
            int durationMs = elapsedMillis(startedAt);

            meterRegistry.counter("webhook_delivery_attempts_total",
                    "outcome", response.statusCode() >= 200 && response.statusCode() < 300 ? "delivered" : "failed")
                    .increment();
            return WebhookDeliveryAttempt.answered(delivery.getId(), attemptNumber, response.statusCode(), url,
                    serializedRequestHeaders, body, objectMapper.writeValueAsString(responseHeaders(response)),
                    responseBody, durationMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            meterRegistry.counter("webhook_delivery_attempts_total", "outcome", "failed").increment();
            return WebhookDeliveryAttempt.transportFailed(delivery.getId(), attemptNumber, url,
                    serializedRequestHeaders, body, elapsedMillis(startedAt), "Delivery thread was interrupted.");
        } catch (Exception e) {
            // Includes connect failures, read timeouts, and TLS errors. The message is
            // truncated to the column width rather than allowed to overflow it.
            meterRegistry.counter("webhook_delivery_attempts_total", "outcome", "failed").increment();
            return WebhookDeliveryAttempt.transportFailed(delivery.getId(), attemptNumber, url,
                    serializedRequestHeaders, body, elapsedMillis(startedAt), truncate(e.toString(), 512));
        }
    }

    /** Everything the receiver sees, recorded verbatim in the attempt so the log shows the real request. */
    private Map<String, String> headersFor(WebhookEvent event, WebhookEndpoint endpoint, String body,
                                           Instant signedAt) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("User-Agent", USER_AGENT);
        headers.put("PaymentFlow-Event-Id", event.getEventRef());
        headers.put("PaymentFlow-Event-Type", event.getEventType());
        headers.put("PaymentFlow-Version", endpoint.getApiVersion());
        headers.put(WebhookSigner.SIGNATURE_HEADER, signer.signatureHeader(body, signedAt, activeSecrets(endpoint)));
        return headers;
    }

    /**
     * The current secret, plus the superseded one while its rotation window is open —
     * so a receiver that has already switched and one that has not both verify (§4.5).
     */
    private List<String> activeSecrets(WebhookEndpoint endpoint) {
        List<String> secrets = new ArrayList<>(2);
        secrets.add(secretCipher.decrypt(endpoint.getSigningSecretEncrypted()));
        if (endpoint.hasUsablePreviousSecret(Instant.now())) {
            secrets.add(secretCipher.decrypt(endpoint.getPreviousSecretEncrypted()));
        }
        return secrets;
    }

    /**
     * Reads at most {@code maxResponseBytes}. Deliberately not
     * {@code BodyHandlers.ofString()}, which buffers the entire response before any cap
     * could be applied — the difference between refusing a hostile response and being
     * defeated by one.
     */
    private String readCapped(InputStream stream) throws IOException {
        try (stream) {
            byte[] buffer = stream.readNBytes(properties.maxResponseBytes());
            return new String(buffer, StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> responseHeaders(HttpResponse<?> response) {
        Map<String, String> headers = new LinkedHashMap<>();
        response.headers().map().forEach((name, values) -> {
            if (headers.size() < MAX_RECORDED_HEADERS) {
                headers.put(name, String.join(",", values));
            }
        });
        return headers;
    }

    private static int elapsedMillis(long startedAtNanos) {
        return (int) Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
