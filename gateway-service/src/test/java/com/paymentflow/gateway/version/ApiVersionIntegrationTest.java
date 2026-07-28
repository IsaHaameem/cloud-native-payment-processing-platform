package com.paymentflow.gateway.version;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Date-based versioning, end to end through a real bound gateway (M21.5, §4.10).
 *
 * <p>§5/M21's testing strategy asks for an E2E in which "two pinned versions served
 * simultaneously produce correctly different shapes" — that is what
 * {@link #twoMerchantsPinnedToDifferentRevisionsSeeDifferentShapes()} is, and it is the
 * assertion the whole milestone exists to make true.
 *
 * <p>The stubs deliberately answer in the <em>current</em> revision's vocabulary (lowercase
 * {@code status}), because that is what every service downstream of the gateway now
 * produces. Any upper case a caller sees is therefore the transformation layer's work and
 * nothing else's, which is what makes these assertions mean something.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ApiVersionIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:8-alpine"))
            .withExposedPorts(6379);

    private static DisposableServer merchantStub;
    private static DisposableServer paymentStub;

    /** The query string the payment stub actually received, for the request-side assertion. */
    private static final AtomicReference<String> lastPaymentQuery = new AtomicReference<>();

    private static final UUID PINNED_MERCHANT_ID = UUID.randomUUID();
    private static final UUID CURRENT_MERCHANT_ID = UUID.randomUUID();

    /** A merchant pinned to the superseded revision — the shape they integrated against. */
    private static final String PINNED_OLD_KEY = "sk_test_pinnedtotheoldrevision";
    /** A merchant pinned to the current revision. */
    private static final String PINNED_CURRENT_KEY = "sk_test_pinnedtothecurrentrev";
    /** A merchant with no pin at all — has never called the public API before. */
    private static final String UNPINNED_KEY = "sk_test_neverhascalledbefore0";

    @LocalServerPort
    private int gatewayPort;

    @BeforeAll
    static void startStubs() {
        merchantStub = HttpServer.create().port(0)
                .route(routes -> routes.post("/internal/v1/api-keys/verify", (req, res) ->
                        req.receive().aggregate().asString().flatMap(body -> {
                            String pin = pinFor(body);
                            UUID merchantId = body.contains(PINNED_OLD_KEY) ? PINNED_MERCHANT_ID : CURRENT_MERCHANT_ID;
                            if (pin == null && !body.contains(UNPINNED_KEY)) {
                                return res.status(404).sendString(Mono.just("{\"code\":\"NOT_FOUND\"}")).then();
                            }
                            return res.header("Content-Type", "application/json").sendString(Mono.just("""
                                    {"merchantId":"%s","keyId":"%s","mode":"TEST","scopes":["payments:read"],
                                     "contactEmail":"billing@acme.test","webhookUrl":null,
                                     "pinnedApiVersion":%s}
                                    """.formatted(merchantId, UUID.randomUUID(),
                                    pin == null ? "null" : "\"" + pin + "\""))).then();
                        })))
                .bindNow();

        paymentStub = HttpServer.create().port(0)
                .route(routes -> routes
                        .get("/v1/payments", (req, res) -> {
                            lastPaymentQuery.set(req.uri());
                            // The current revision's vocabulary, lowercase — what
                            // payment-service actually emits after M21.5.
                            return res.header("Content-Type", "application/json").sendString(Mono.just("""
                                    {"object":"list","has_more":false,
                                     "data":[{"id":"pay_1","object":"payment","status":"authorized"},
                                             {"id":"pay_2","object":"payment","status":"partially_refunded"}]}"""));
                        })
                        .get("/v1/payments/**", (req, res) -> {
                            lastPaymentQuery.set(req.uri());
                            return res.header("Content-Type", "application/json").sendString(Mono.just(
                                    "{\"id\":\"pay_1\",\"object\":\"payment\",\"status\":\"captured\"}"));
                        }))
                .bindNow();
    }

    private static String pinFor(String verifyRequestBody) {
        if (verifyRequestBody.contains(PINNED_OLD_KEY)) {
            return "2026-07-27";
        }
        if (verifyRequestBody.contains(PINNED_CURRENT_KEY)) {
            return "2026-08-01";
        }
        return null;
    }

    @AfterAll
    static void stopStubs() {
        if (merchantStub != null) {
            merchantStub.disposeNow();
        }
        if (paymentStub != null) {
            paymentStub.disposeNow();
        }
    }

    @AfterEach
    void reset() {
        lastPaymentQuery.set(null);
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("paymentflow.services.merchant.base-uri", () -> "http://localhost:" + merchantStub.port());
        registry.add("paymentflow.services.payment.base-uri", () -> "http://localhost:" + paymentStub.port());
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + gatewayPort)
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ── The assertion the milestone exists to make ───────────────────────────────────

    @Test
    void twoMerchantsPinnedToDifferentRevisionsSeeDifferentShapes() {
        // §5/M21's E2E criterion, in one test: the same endpoint, the same upstream
        // response, two callers, two contracts — simultaneously, with no server
        // reconfiguration between them.
        client().get().uri("/v1/payments").header("Authorization", "Bearer " + PINNED_OLD_KEY)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("PaymentFlow-Version", "2026-07-27")
                .expectBody()
                .jsonPath("$.data[0].status").isEqualTo("AUTHORIZED")
                .jsonPath("$.data[1].status").isEqualTo("PARTIALLY_REFUNDED");

        client().get().uri("/v1/payments").header("Authorization", "Bearer " + PINNED_CURRENT_KEY)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("PaymentFlow-Version", "2026-08-01")
                .expectBody()
                .jsonPath("$.data[0].status").isEqualTo("authorized")
                .jsonPath("$.data[1].status").isEqualTo("partially_refunded");
    }

    @Test
    void aSingleObjectResponseIsTransformedToo() {
        client().get().uri("/v1/payments/pay_1").header("Authorization", "Bearer " + PINNED_OLD_KEY)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("CAPTURED");
    }

    @Test
    void anUnpinnedMerchantGetsTheCurrentRevision() {
        // A merchant whose pin has not been written yet — the gateway serves current rather
        // than failing or guessing.
        client().get().uri("/v1/payments").header("Authorization", "Bearer " + UNPINNED_KEY)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("PaymentFlow-Version", "2026-08-01")
                .expectBody().jsonPath("$.data[0].status").isEqualTo("authorized");
    }

    // ── Per-request override ─────────────────────────────────────────────────────────

    @Test
    void theHeaderOverridesThePinInBothDirections() {
        // A pinned-old merchant trying the new shape for one call: the upgrade path.
        client().get().uri("/v1/payments")
                .header("Authorization", "Bearer " + PINNED_OLD_KEY)
                .header("PaymentFlow-Version", "2026-08-01")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("PaymentFlow-Version", "2026-08-01")
                .expectBody().jsonPath("$.data[0].status").isEqualTo("authorized");

        // ...and a current merchant reproducing a bug report against the old shape.
        client().get().uri("/v1/payments")
                .header("Authorization", "Bearer " + PINNED_CURRENT_KEY)
                .header("PaymentFlow-Version", "2026-07-27")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("PaymentFlow-Version", "2026-07-27")
                .expectBody().jsonPath("$.data[0].status").isEqualTo("AUTHORIZED");
    }

    @Test
    void anUnsupportedVersionIsRejectedWithACataloguedError() {
        client().get().uri("/v1/payments")
                .header("Authorization", "Bearer " + PINNED_CURRENT_KEY)
                .header("PaymentFlow-Version", "2027-01-01")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                // The M21.4 contract, on an error M21.5 introduced — the two milestones
                // meeting is worth asserting rather than assuming.
                .jsonPath("$.code").isEqualTo("UNSUPPORTED_API_VERSION")
                .jsonPath("$.type").isEqualTo("invalid_request_error")
                .jsonPath("$.docUrl").isEqualTo("https://docs.paymentflow.dev/errors#unsupported_api_version")
                .jsonPath("$.message").value(message ->
                        assertThat((String) message).contains("2026-08-01"));
    }

    @Test
    void aMalformedVersionIsRejectedTheSameWay() {
        client().get().uri("/v1/payments")
                .header("Authorization", "Bearer " + PINNED_CURRENT_KEY)
                .header("PaymentFlow-Version", "latest")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("UNSUPPORTED_API_VERSION");
    }

    // ── Deprecation signalling ───────────────────────────────────────────────────────

    @Test
    void aSupersededRevisionCarriesDeprecationAndSunsetHeaders() {
        // The half of §4.10 that makes the deprecation timeline real rather than published:
        // a client on the old revision is told, on every response, in a form tooling reads.
        client().get().uri("/v1/payments").header("Authorization", "Bearer " + PINNED_OLD_KEY)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Deprecation", "true")
                .expectHeader().valueEquals("Sunset", "Sun, 01 Aug 2027 00:00:00 GMT")
                .expectHeader().value("Link", link ->
                        assertThat(link).contains("rel=\"deprecation\""));
    }

    @Test
    void theCurrentRevisionCarriesNoDeprecationHeaders() {
        // The complement, and the one that would fail if the headers were set
        // unconditionally — which would tell every caller on the newest contract that it is
        // going away.
        client().get().uri("/v1/payments").header("Authorization", "Bearer " + PINNED_CURRENT_KEY)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist("Deprecation")
                .expectHeader().doesNotExist("Sunset");
    }

    // ── Request-side transformation ──────────────────────────────────────────────────

    @Test
    void theStatusFilterIsRewrittenBeforeItReachesTheService() {
        // The request half. Asserted against what the upstream actually received, because
        // that is the only place the rewrite is observable — payment-service happens to
        // parse the filter case-insensitively today, so a broken transformation here would
        // not change any response body. Verifying the outbound request rather than the
        // response is what makes this test fail if the rewrite stops happening.
        client().get().uri("/v1/payments?status=AUTHORIZED&limit=20")
                .header("Authorization", "Bearer " + PINNED_OLD_KEY)
                .exchange()
                .expectStatus().isOk();

        assertThat(lastPaymentQuery.get()).contains("status=authorized").doesNotContain("status=AUTHORIZED");
        assertThat(lastPaymentQuery.get()).contains("limit=20");
    }

    @Test
    void aCurrentRevisionRequestIsPassedThroughUnchanged() {
        client().get().uri("/v1/payments?status=authorized")
                .header("Authorization", "Bearer " + PINNED_CURRENT_KEY)
                .exchange()
                .expectStatus().isOk();

        assertThat(lastPaymentQuery.get()).contains("status=authorized");
    }
}
