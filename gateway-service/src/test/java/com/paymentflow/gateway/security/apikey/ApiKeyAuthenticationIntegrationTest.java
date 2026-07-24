package com.paymentflow.gateway.security.apikey;

import com.paymentflow.common.security.InternalContextHeaders;
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
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Black-box verification of the M15 API-key path (§4.3), against a real bound gateway,
 * real Redis (the verify cache), and Reactor Netty stubs standing in for merchant-service's
 * verify endpoint and payment-service's {@code /api/v1/payments} — this is the milestone's
 * own highest-risk regression surface (the JWT path itself is covered unchanged by
 * {@link com.paymentflow.gateway.GatewayIntegrationTest}, deliberately not touched here).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ApiKeyAuthenticationIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:8-alpine"))
            .withExposedPorts(6379);

    private static DisposableServer merchantStub;
    private static DisposableServer paymentStub;
    private static DisposableServer sandboxStub;
    private static final AtomicReference<String> lastReceivedInternalMerchantId = new AtomicReference<>();
    private static final AtomicReference<String> lastReceivedAuthorization = new AtomicReference<>();
    private static final AtomicReference<String> lastSandboxReceivedInternalMerchantId = new AtomicReference<>();
    private static final AtomicReference<String> lastSandboxReceivedInternalMode = new AtomicReference<>();

    private static final UUID VALID_MERCHANT_ID = UUID.randomUUID();
    private static final UUID VALID_KEY_ID = UUID.randomUUID();
    private static final String VALID_SECRET_KEY = "sk_test_validkeyforthistestonly";
    private static final String PUBLISHABLE_KEY = "pk_test_publishablekeyforthis";

    @LocalServerPort
    private int gatewayPort;

    private WebTestClient client;

    @BeforeAll
    static void startStubs() {
        merchantStub = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/internal/v1/api-keys/verify", (req, res) -> req.receive().aggregate()
                        .asString()
                        .flatMap(body -> {
                            if (body.contains(VALID_SECRET_KEY) || body.contains(PUBLISHABLE_KEY)) {
                                return res.header("Content-Type", "application/json").sendString(Mono.just("""
                                        {"merchantId":"%s","keyId":"%s","mode":"TEST",
                                         "scopes":["%s"],"contactEmail":"billing@acme.test","webhookUrl":null}
                                        """.formatted(VALID_MERCHANT_ID, VALID_KEY_ID,
                                        body.contains(PUBLISHABLE_KEY) ? "payments:read" : "payments:write")))
                                        .then();
                            }
                            return res.status(404).sendString(Mono.just("{\"code\":\"NOT_FOUND\"}")).then();
                        })))
                .bindNow();

        paymentStub = HttpServer.create()
                .port(0)
                .route(routes -> routes
                        .get("/api/v1/payments", ApiKeyAuthenticationIntegrationTest::paymentStubResponse)
                        .get("/api/v1/payments/**", ApiKeyAuthenticationIntegrationTest::paymentStubResponse)
                        .post("/api/v1/payments", ApiKeyAuthenticationIntegrationTest::paymentStubResponse)
                        .post("/api/v1/payments/**", ApiKeyAuthenticationIntegrationTest::paymentStubResponse))
                .bindNow();

        sandboxStub = HttpServer.create()
                .port(0)
                .route(routes -> routes
                        .post("/v1/test/simulations", (req, res) -> {
                            lastSandboxReceivedInternalMerchantId.set(req.requestHeaders().get(InternalContextHeaders.MERCHANT_ID));
                            lastSandboxReceivedInternalMode.set(req.requestHeaders().get(InternalContextHeaders.MODE));
                            return res.status(201).header("Content-Type", "application/json")
                                    .sendString(Mono.just("""
                                            {"id":"%s","scenario":"FORCE_RATE_LIMIT","declineCode":null,"errorCode":null,
                                             "latencyMs":null,"remainingCount":3,"expiresAt":null,"enactedFrom":null}
                                            """.formatted(UUID.randomUUID())));
                        })
                        .get("/v1/test/decisions", (req, res) -> {
                            lastSandboxReceivedInternalMerchantId.set(req.requestHeaders().get(InternalContextHeaders.MERCHANT_ID));
                            lastSandboxReceivedInternalMode.set(req.requestHeaders().get(InternalContextHeaders.MODE));
                            return res.status(200).header("Content-Type", "application/json")
                                    .sendString(Mono.just(
                                            "{\"content\":[],\"page\":0,\"size\":20,\"totalElements\":0,"
                                                    + "\"totalPages\":0,\"first\":true,\"last\":true}"));
                        })
                        .get("/v1/test/cards", (req, res) -> res.status(200)
                                .header("Content-Type", "application/json")
                                .sendString(Mono.just("[]"))))
                .bindNow();
    }

    /** Registered for both the bare path and {@code /**} — Reactor Netty's own path matcher, unlike Spring's
     *  PathPattern, does not treat {@code /**} as matching zero trailing segments. */
    private static Publisher<Void> paymentStubResponse(HttpServerRequest req, HttpServerResponse res) {
        lastReceivedInternalMerchantId.set(req.requestHeaders().get(InternalContextHeaders.MERCHANT_ID));
        lastReceivedAuthorization.set(req.requestHeaders().get("Authorization"));
        return res.header("Content-Type", "application/json")
                .sendString(Mono.just("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"CREATED\"}"));
    }

    @AfterAll
    static void stopStubs() {
        if (merchantStub != null) {
            merchantStub.disposeNow();
        }
        if (paymentStub != null) {
            paymentStub.disposeNow();
        }
        if (sandboxStub != null) {
            sandboxStub.disposeNow();
        }
    }

    @AfterEach
    void resetCaptured() {
        lastReceivedInternalMerchantId.set(null);
        lastReceivedAuthorization.set(null);
        lastSandboxReceivedInternalMerchantId.set(null);
        lastSandboxReceivedInternalMode.set(null);
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("paymentflow.services.merchant.base-uri", () -> "http://localhost:" + merchantStub.port());
        registry.add("paymentflow.services.payment.base-uri", () -> "http://localhost:" + paymentStub.port());
        registry.add("paymentflow.services.sandbox.base-uri", () -> "http://localhost:" + sandboxStub.port());
    }

    private WebTestClient client() {
        if (client == null) {
            client = WebTestClient.bindToServer()
                    .baseUrl("http://localhost:" + gatewayPort)
                    .responseTimeout(Duration.ofSeconds(10))
                    .build();
        }
        return client;
    }

    @Test
    void aValidSecretKeyAuthenticatesAndTheRequestIsProxiedWithASignedInternalContext() {
        client().post().uri("/v1/payments")
                .header("Authorization", "Bearer " + VALID_SECRET_KEY)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isOk();

        assertThat(lastReceivedInternalMerchantId.get()).isEqualTo(VALID_MERCHANT_ID.toString());
        // The client's raw API key must NOT be forwarded downstream: payment-service's
        // OAuth2 resource server would try to decode it as a JWT and reject the request.
        // The signed internal context above is the credential from the gateway onward.
        assertThat(lastReceivedAuthorization.get()).isNull();
    }

    @Test
    void anUnknownKeyIsRejectedWith401() {
        client().get().uri("/v1/payments")
                .header("Authorization", "Bearer sk_test_totallymadeupandunknown")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHORIZED");
    }

    @Test
    void aPublishableKeyCannotWrite() {
        client().post().uri("/v1/payments")
                .header("Authorization", "Bearer " + PUBLISHABLE_KEY)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("INSUFFICIENT_SCOPE");
    }

    @Test
    void aPublishableKeyCanRead() {
        client().get().uri("/v1/payments")
                .header("Authorization", "Bearer " + PUBLISHABLE_KEY)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void aClientSuppliedInternalHeaderIsStrippedAndNeverForwarded() {
        client().post().uri("/v1/payments")
                .header("Authorization", "Bearer " + VALID_SECRET_KEY)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header(InternalContextHeaders.MERCHANT_ID, UUID.randomUUID().toString()) // forged, must be stripped
                .exchange()
                .expectStatus().isOk();

        // The gateway's own signed value reached payment-service, not the forged one.
        assertThat(lastReceivedInternalMerchantId.get()).isEqualTo(VALID_MERCHANT_ID.toString());
    }

    @Test
    void theExistingJwtPathIsUnaffectedByApiKeyRoutes() {
        // No Authorization header at all on a /v1 route: correctly 401, proving the new
        // chain doesn't accidentally permitAll() unauthenticated traffic (fail-closed).
        client().get().uri("/v1/payments")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void aValidSecretKeyReachesSandboxSimulationsWithASignedInternalContext() {
        // M17.5: sandbox-service's control API is a direct passthrough route (no
        // /api/v1 rewrite exists on that side) — proving the route itself resolves and
        // carries the same signed context every other API-key route does.
        client().post().uri("/v1/test/simulations")
                .header("Authorization", "Bearer " + VALID_SECRET_KEY)
                .exchange()
                .expectStatus().isCreated();

        assertThat(lastSandboxReceivedInternalMerchantId.get()).isEqualTo(VALID_MERCHANT_ID.toString());
        assertThat(lastSandboxReceivedInternalMode.get()).isEqualToIgnoringCase("TEST");
    }

    @Test
    void aValidSecretKeyReachesTheDecisionLogQueryApiWithASignedInternalContext() {
        // M17.8: a second, independent route entry (not a broadened M17.5 predicate) —
        // proving it resolves on its own confirms both routes coexist correctly.
        client().get().uri("/v1/test/decisions")
                .header("Authorization", "Bearer " + VALID_SECRET_KEY)
                .exchange()
                .expectStatus().isOk();

        assertThat(lastSandboxReceivedInternalMerchantId.get()).isEqualTo(VALID_MERCHANT_ID.toString());
        assertThat(lastSandboxReceivedInternalMode.get()).isEqualToIgnoringCase("TEST");
    }

    @Test
    void theTestCardCatalogueIsReachableWithNoAuthorizationHeaderAtAll() {
        // M17.8: unlike every other /v1/** route, the catalogue carries no merchant/mode
        // context and is deliberately public (TestCardController's own M17.1 javadoc) —
        // proving it resolves with zero Authorization header confirms chain #1's permitAll
        // actually took effect, not just that the route predicate matches.
        client().get().uri("/v1/test/cards")
                .exchange()
                .expectStatus().isOk();
    }
}
