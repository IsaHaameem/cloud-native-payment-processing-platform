package com.paymentflow.notification.sandbox;

import com.paymentflow.common.security.InternalContextHeaders;
import com.paymentflow.common.security.InternalContextProperties;
import com.paymentflow.common.security.InternalContextSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads a merchant's active simulation override from sandbox-service, so M18's delivery
 * pipeline can enact D131's two webhook-path scenarios.
 *
 * <p>This is notification-service's <b>only</b> synchronous dependency on another
 * service, and it is deliberately built to be ignorable. Every failure mode — sandbox
 * unreachable, slow, returning nonsense, or simply having no override — resolves to
 * {@link Optional#empty()}, which means "behave normally". A simulation feature must
 * never be able to affect a real delivery by being unavailable; that would make a test
 * convenience into a production dependency, which is the opposite of D103's whole point
 * about sandbox being advisory.
 *
 * <p>Only consulted in <b>test</b> mode. Live-mode deliveries never make this call at
 * all — not because the override would be rejected (sandbox's own schema makes a
 * live-mode override impossible, M17.5), but because a live delivery should not depend on
 * sandbox-service being up even to be told "no".
 *
 * <p>Authenticates with the same HMAC-signed internal context payment-service uses to
 * call sandbox (M17.2/D100) — notification-service becomes the second service to sign
 * this header family, with no new mechanism introduced.
 */
@Component
public class SandboxScenarioClient {

    private static final Logger log = LoggerFactory.getLogger(SandboxScenarioClient.class);
    private static final String TEST_MODE = "test";
    private static final UUID SERVICE_KEY_ID = UUID.randomUUID();
    private static final String SERVICE_SCOPES = "webhooks:manage";

    private final RestClient restClient;
    private final InternalContextSigner signer;
    private final InternalContextProperties internalContextProperties;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public SandboxScenarioClient(SandboxClientProperties clientProperties,
                                 InternalContextProperties internalContextProperties,
                                 InternalContextSigner signer, ObjectMapper objectMapper) {
        this.restClient = RestClient.builder()
                .baseUrl(clientProperties.baseUri())
                .requestFactory(timeoutFactory(clientProperties.timeout()))
                .build();
        this.signer = signer;
        this.internalContextProperties = internalContextProperties;
        this.objectMapper = objectMapper;
        this.enabled = clientProperties.enabled();
    }

    /** Where sandbox-service lives, and how long we are willing to wait for it. */
    @ConfigurationProperties(prefix = "paymentflow.webhooks.sandbox")
    public record SandboxClientProperties(String baseUri, Duration timeout, boolean enabled) {
    }

    /** The active webhook-path scenario for this merchant, or empty for "behave normally". */
    public Optional<SandboxWebhookScenario> activeScenario(UUID merchantId, String mode) {
        if (!enabled || !TEST_MODE.equals(mode)) {
            return Optional.empty();
        }
        try {
            String body = restClient.get()
                    .uri("/v1/test/simulations/active")
                    .headers(headers -> signInto(headers::set, merchantId, mode))
                    .retrieve()
                    .body(String.class);
            if (body == null || body.isBlank()) {
                return Optional.empty();
            }
            JsonNode scenario = objectMapper.readTree(body).get("scenario");
            if (scenario == null || scenario.isNull()) {
                return Optional.empty();
            }
            return parse(scenario.asString());
        } catch (Exception e) {
            // Deliberately swallowed at debug level. A 404 (no active override) is the
            // common case and not noteworthy; a real outage must not turn a delivery into
            // an error, and must not fill the logs on every delivery either.
            log.debug("Could not read the active sandbox scenario for merchant {}: {}", merchantId, e.toString());
            return Optional.empty();
        }
    }

    private static Optional<SandboxWebhookScenario> parse(String scenario) {
        for (SandboxWebhookScenario candidate : SandboxWebhookScenario.values()) {
            if (candidate.name().equalsIgnoreCase(scenario)) {
                return Optional.of(candidate);
            }
        }
        // One of sandbox's six engine scenarios (force_decline, inject_latency, …). Those
        // are payment-path concerns; webhook delivery correctly ignores them.
        return Optional.empty();
    }

    private void signInto(java.util.function.BiConsumer<String, String> header, UUID merchantId, String mode) {
        long issuedAt = Instant.now().getEpochSecond();
        String signature = signer.sign(internalContextProperties.secret(), merchantId.toString(), mode,
                SERVICE_KEY_ID.toString(), SERVICE_SCOPES, null, null, issuedAt);
        header.accept(InternalContextHeaders.MERCHANT_ID, merchantId.toString());
        header.accept(InternalContextHeaders.MODE, mode);
        header.accept(InternalContextHeaders.KEY_ID, SERVICE_KEY_ID.toString());
        header.accept(InternalContextHeaders.SCOPES, SERVICE_SCOPES);
        header.accept(InternalContextHeaders.ISSUED_AT, Long.toString(issuedAt));
        header.accept(InternalContextHeaders.SIGNATURE, signature);
    }

    private static org.springframework.http.client.ClientHttpRequestFactory timeoutFactory(Duration timeout) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) timeout.toMillis());
        factory.setReadTimeout((int) timeout.toMillis());
        return factory;
    }
}
