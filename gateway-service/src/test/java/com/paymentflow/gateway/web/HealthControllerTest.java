package com.paymentflow.gateway.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The liveness endpoint's contract, bound straight to the controller — no Spring context, no
 * Redis, no Docker. Deliberate: what this file asserts is the response itself, and a probe that
 * needed the whole application to start in order to be tested would be evidence against its own
 * shallowness.
 *
 * <p>The half this cannot see — that the gateway serves it to a caller holding no credential,
 * through the real two-chain security filter — is asserted where the real chain exists, by
 * {@code GatewayIntegrationTest.healthEndpointIsPublicAndShallow()}. The two together cover the
 * endpoint; neither is sufficient alone.
 */
class HealthControllerTest {

    private final WebTestClient client = WebTestClient.bindToController(new HealthController())
            .configureClient()
            .responseTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    void returns200WithTheDocumentedJsonBody() {
        client.get().uri("/health")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo("ok");
    }

    @Test
    void theBodyIsExactlyTheOneFieldAndNothingElse() {
        // A liveness body that grows a field is a liveness body that has started reporting on
        // something, which is how a probe quietly acquires a dependency. Pin the whole payload.
        client.get().uri("/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("{\"status\":\"ok\"}", JsonCompareMode.STRICT);
    }

    @Test
    void isNotCacheable() {
        // A cached 200 from an intermediary would keep reporting a healthy gateway after it
        // stopped being one.
        client.get().uri("/health")
                .exchange()
                .expectHeader().cacheControl(CacheControl.noStore());
    }

    @Test
    void answersHeadAsWellAsGet() {
        // External uptime monitors commonly poll with HEAD; SecurityConfig permits it, so the
        // handler has to answer it.
        client.head().uri("/health")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void isCheapEnoughToPollAndAllocatesNothingPerRequest() {
        // The point of the endpoint. If a future edit reaches for a database, a downstream call,
        // or anything else that blocks, this is what notices: a thousand sequential requests
        // against an in-memory binding stay far under a second.
        long startedAt = System.nanoTime();
        IntStream.range(0, 1_000).forEach(i ->
                client.get().uri("/health").exchange().expectStatus().isOk());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed)
                .describedAs("1000 sequential /health calls took %s — something in the handler "
                        + "is doing real work", elapsed)
                .isLessThan(Duration.ofSeconds(5));
    }
}
