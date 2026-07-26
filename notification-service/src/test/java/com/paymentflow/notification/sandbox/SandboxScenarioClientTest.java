package com.paymentflow.notification.sandbox;

import com.paymentflow.common.security.InternalContextProperties;
import com.paymentflow.common.security.InternalContextSigner;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D131's enactment path, and specifically its <b>failure</b> modes (M18.8).
 *
 * <p>This is notification-service's only synchronous dependency on another service, and
 * the property that matters most is that it is ignorable: every way sandbox-service can
 * misbehave has to resolve to "behave normally", because a simulation feature that can
 * break a real delivery by being unavailable is worse than no simulation feature.
 */
class SandboxScenarioClientTest {

    private HttpServer sandbox;
    private final AtomicReference<String> responseBody = new AtomicReference<>("{}");
    private final AtomicReference<Integer> responseStatus = new AtomicReference<>(200);
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> receivedMerchantId = new AtomicReference<>();

    @BeforeEach
    void startSandbox() throws IOException {
        sandbox = HttpServer.create(new InetSocketAddress(0), 0);
        sandbox.createContext("/v1/test/simulations/active", exchange -> {
            calls.incrementAndGet();
            receivedMerchantId.set(exchange.getRequestHeaders().getFirst("X-PF-Internal-Merchant-Id"));
            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        sandbox.start();
    }

    @AfterEach
    void stopSandbox() {
        sandbox.stop(0);
    }

    private SandboxScenarioClient client(boolean enabled, String baseUri, Duration timeout) {
        return new SandboxScenarioClient(
                new SandboxScenarioClient.SandboxClientProperties(baseUri, timeout, enabled),
                new InternalContextProperties("dev-only-insecure-shared-secret-change-me", 30),
                new InternalContextSigner(), JsonMapper.builder().build());
    }

    private SandboxScenarioClient client() {
        return client(true, "http://localhost:" + sandbox.getAddress().getPort(), Duration.ofSeconds(2));
    }

    @Test
    void aWebhookScenarioIsReadAndReturned() {
        responseBody.set("{\"scenario\":\"WEBHOOK_FAILURE\",\"remainingCount\":3}");

        assertThat(client().activeScenario(UUID.randomUUID(), "test"))
                .contains(SandboxWebhookScenario.WEBHOOK_FAILURE);

        responseBody.set("{\"scenario\":\"DUPLICATE_WEBHOOKS\"}");
        assertThat(client().activeScenario(UUID.randomUUID(), "test"))
                .contains(SandboxWebhookScenario.DUPLICATE_WEBHOOKS);
    }

    @Test
    void theRequestCarriesTheMerchantsSignedInternalContext() {
        responseBody.set("{\"scenario\":\"WEBHOOK_FAILURE\"}");
        UUID merchantId = UUID.randomUUID();

        client().activeScenario(merchantId, "test");

        // The same mechanism payment-service uses to call sandbox (D100) — no new auth
        // pattern introduced for this one caller.
        assertThat(receivedMerchantId.get()).isEqualTo(merchantId.toString());
    }

    @Test
    void anEngineScenarioIsIgnoredBecauseItIsNotAWebhookConcern() {
        // sandbox's vocabulary is eight values; only two of them mean anything here.
        responseBody.set("{\"scenario\":\"FORCE_DECLINE\",\"declineCode\":\"card_declined\"}");

        assertThat(client().activeScenario(UUID.randomUUID(), "test")).isEmpty();
    }

    @Test
    void liveModeNeverCallsSandboxAtAll() {
        responseBody.set("{\"scenario\":\"WEBHOOK_FAILURE\"}");
        calls.set(0);

        assertThat(client().activeScenario(UUID.randomUUID(), "live")).isEmpty();

        // Not merely "returns empty": a live delivery must not depend on sandbox-service
        // being reachable even to be told no.
        assertThat(calls.get()).isZero();
    }

    @Test
    void everyFailureModeResolvesToBehaveNormally() {
        UUID merchantId = UUID.randomUUID();

        // 404 — the common case: no active override.
        responseStatus.set(404);
        responseBody.set("{\"code\":\"NOT_FOUND\"}");
        assertThat(client().activeScenario(merchantId, "test")).isEmpty();

        // 500 — sandbox is broken.
        responseStatus.set(500);
        assertThat(client().activeScenario(merchantId, "test")).isEmpty();

        // 200 with nonsense.
        responseStatus.set(200);
        responseBody.set("not json at all");
        assertThat(client().activeScenario(merchantId, "test")).isEmpty();

        // 200 with a null scenario.
        responseBody.set("{\"scenario\":null}");
        assertThat(client().activeScenario(merchantId, "test")).isEmpty();

        // 200 with an empty body.
        responseBody.set("");
        assertThat(client().activeScenario(merchantId, "test")).isEmpty();
    }

    @Test
    void anUnreachableSandboxResolvesToBehaveNormally() {
        // Port 1 is reserved and refuses instantly — a stand-in for sandbox being down,
        // asserted rather than assumed, because this is the failure that would otherwise
        // turn a simulation feature into a production dependency.
        SandboxScenarioClient offline = client(true, "http://localhost:1", Duration.ofMillis(200));

        assertThat(offline.activeScenario(UUID.randomUUID(), "test")).isEmpty();
    }

    @Test
    void disablingTheIntegrationSkipsTheCallEntirely() {
        responseBody.set("{\"scenario\":\"WEBHOOK_FAILURE\"}");
        calls.set(0);
        SandboxScenarioClient disabled =
                client(false, "http://localhost:" + sandbox.getAddress().getPort(), Duration.ofSeconds(2));

        Optional<SandboxWebhookScenario> scenario = disabled.activeScenario(UUID.randomUUID(), "test");

        assertThat(scenario).isEmpty();
        assertThat(calls.get()).isZero();
    }
}
