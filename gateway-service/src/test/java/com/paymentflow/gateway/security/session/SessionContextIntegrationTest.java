package com.paymentflow.gateway.security.session;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.paymentflow.common.security.InternalContextHeaders;
import com.paymentflow.common.security.InternalContextSigner;
import com.paymentflow.common.security.InternalPrincipal;
import com.paymentflow.gateway.security.ApiScopes;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
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
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Black-box verification of M23.0's developer-portal session path (D183), against a real
 * bound gateway, real Redis (the merchant-lookup cache), real RSA key material behind a
 * JWKS stub, and Reactor Netty stubs standing in for merchant-service's internal lookup and
 * for two downstream {@code /v1} services.
 *
 * <p>Structured like {@code ApiKeyAuthenticationIntegrationTest} on purpose — this is the
 * same question asked of the second credential, and the two paths have to be shown not to
 * interfere. The API-key path's own regression surface stays covered there, untouched.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SessionContextIntegrationTest {

    private static final String ISSUER = "https://identity.paymentflow.local";
    private static final String SECRET = "session-context-integration-secret";

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:8-alpine"))
            .withExposedPorts(6379);

    private static RSAKey rsaKey;
    private static DisposableServer identityStub;
    private static DisposableServer merchantStub;
    private static DisposableServer paymentStub;
    private static DisposableServer auditStub;
    private static DisposableServer sandboxStub;

    /** The one user the merchant stub knows about. Every other subject is "not onboarded". */
    private static final UUID ONBOARDED_USER_ID = UUID.randomUUID();
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID TEST_KEY_ID = UUID.randomUUID();
    private static final String CONTACT_EMAIL = "portal@acme.test";
    /** A test-mode secret key belonging to the same merchant as the session above. */
    private static final String TEST_MODE_SECRET_KEY = "sk_test_fake_session_ctx_fixture";

    private static final AtomicReference<CapturedRequest> lastDownstream = new AtomicReference<>();

    private static final InternalContextSigner SIGNER = new InternalContextSigner();

    @LocalServerPort
    private int gatewayPort;

    private WebTestClient client;

    @BeforeAll
    static void startStubs() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID("test-key")
                .build();
        String jwksJson = new JWKSet(rsaKey.toPublicJWK()).toString();

        identityStub = HttpServer.create()
                .port(0)
                .route(routes -> routes.get("/oauth2/jwks", (req, res) -> res
                        .header("Content-Type", "application/json")
                        .sendString(Mono.just(jwksJson))))
                .bindNow();

        merchantStub = HttpServer.create()
                .port(0)
                .route(routes -> routes
                        .get("/internal/v1/merchants/by-owner/{ownerUserId}", (req, res) -> {
                            if (ONBOARDED_USER_ID.toString().equals(req.param("ownerUserId"))) {
                                return res.header("Content-Type", "application/json").sendString(Mono.just("""
                                        {"merchantId":"%s","contactEmail":"%s","webhookUrl":null}
                                        """.formatted(MERCHANT_ID, CONTACT_EMAIL)));
                            }
                            return res.status(404).sendString(Mono.just("{\"code\":\"NOT_FOUND\"}")).then();
                        })
                        // Present so this class can assert the two credential paths do not
                        // interfere — specifically that adding a filter which *reads*
                        // X-PF-Mode did not make that header reachable from an API key.
                        .post("/internal/v1/api-keys/verify", (req, res) -> res
                                .header("Content-Type", "application/json").sendString(Mono.just("""
                                        {"merchantId":"%s","keyId":"%s","mode":"TEST",
                                         "scopes":["payments:read"],"contactEmail":"%s","webhookUrl":null}
                                        """.formatted(MERCHANT_ID, TEST_KEY_ID, CONTACT_EMAIL)))))
                .bindNow();

        paymentStub = HttpServer.create()
                .port(0)
                .route(routes -> routes
                        .get("/v1/payments", SessionContextIntegrationTest::capture)
                        .get("/v1/payments/**", SessionContextIntegrationTest::capture)
                        .post("/v1/payments", SessionContextIntegrationTest::capture)
                        .post("/v1/payments/**", SessionContextIntegrationTest::capture))
                .bindNow();

        auditStub = HttpServer.create()
                .port(0)
                .route(routes -> routes
                        .get("/v1/events", SessionContextIntegrationTest::capture)
                        .get("/v1/events/**", SessionContextIntegrationTest::capture))
                .bindNow();

        sandboxStub = HttpServer.create()
                .port(0)
                .route(routes -> routes
                        .get("/v1/test/cards", SessionContextIntegrationTest::capture)
                        .get("/v1/test/cards/**", SessionContextIntegrationTest::capture))
                .bindNow();
    }

    @AfterAll
    static void stopStubs() {
        for (DisposableServer server : List.of(identityStub, merchantStub, paymentStub, auditStub, sandboxStub)) {
            if (server != null) {
                server.disposeNow();
            }
        }
    }

    @AfterEach
    void reset() {
        lastDownstream.set(null);
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("paymentflow.services.identity.base-uri", () -> "http://localhost:" + identityStub.port());
        registry.add("paymentflow.services.merchant.base-uri", () -> "http://localhost:" + merchantStub.port());
        registry.add("paymentflow.services.payment.base-uri", () -> "http://localhost:" + paymentStub.port());
        registry.add("paymentflow.services.audit.base-uri", () -> "http://localhost:" + auditStub.port());
        registry.add("paymentflow.services.sandbox.base-uri", () -> "http://localhost:" + sandboxStub.port());
        registry.add("paymentflow.internal-context.secret", () -> SECRET);
    }

    // ---------------------------------------------------------------------------------
    // The core claim: a session reaches /v1 with a context indistinguishable in shape
    // from an API key's, and downstream verifies it with the same secret.
    // ---------------------------------------------------------------------------------

    @Test
    void aSessionReachesThePublicTierWithASignedInternalContext() throws Exception {
        client().get().uri("/v1/payments")
                .header("Authorization", "Bearer " + signedJwt(ONBOARDED_USER_ID.toString()))
                .exchange()
                .expectStatus().isOk();

        CapturedRequest captured = lastDownstream.get();
        assertThat(captured).isNotNull();
        assertThat(captured.merchantId()).isEqualTo(MERCHANT_ID.toString());
        assertThat(captured.principal()).isEqualTo(InternalPrincipal.SESSION.wireValue());
        assertThat(captured.userId()).isEqualTo(ONBOARDED_USER_ID.toString());
        assertThat(captured.keyId()).isNull();
        assertThat(captured.contactEmail()).isEqualTo(CONTACT_EMAIL);
    }

    @Test
    void theAssertedContextVerifiesAgainstTheSharedSecret() throws Exception {
        client().get().uri("/v1/payments")
                .header("Authorization", "Bearer " + signedJwt(ONBOARDED_USER_ID.toString()))
                .exchange()
                .expectStatus().isOk();

        CapturedRequest captured = lastDownstream.get();
        // Exactly what InternalContextFilter does in every downstream service. If this
        // passes, the six services behind /v1 need no change at all — which is the entire
        // argument for building M23.0 instead of a second controller tier (D182).
        boolean valid = SIGNER.matches(SECRET, captured.merchantId(), captured.mode(),
                InternalPrincipal.SESSION, captured.userId(), null, captured.scopes(),
                captured.contactEmail(), captured.webhookUrl(), Long.parseLong(captured.issuedAt()),
                captured.signature());

        assertThat(valid).isTrue();
    }

    @Test
    void theSessionCredentialIsReplacedNotSupplemented() throws Exception {
        client().get().uri("/v1/payments")
                .header("Authorization", "Bearer " + signedJwt(ONBOARDED_USER_ID.toString()))
                .exchange()
                .expectStatus().isOk();

        // payment-service is the one /v1 service with an OAuth2 resource server. If the JWT
        // survived this hop it would authenticate the *user* there and scope the request by
        // a path this filter deliberately does not take.
        assertThat(lastDownstream.get().authorization()).isNull();
    }

    @Test
    void aSessionReachesAServiceThatHasNoResourceServerOfItsOwn() throws Exception {
        // audit-service authenticates with InternalContextFilter and nothing else (D133).
        // Before M23.0 no browser session could reach it by any route.
        client().get().uri("/v1/events")
                .header("Authorization", "Bearer " + signedJwt(ONBOARDED_USER_ID.toString()))
                .exchange()
                .expectStatus().isOk();

        assertThat(lastDownstream.get().merchantId()).isEqualTo(MERCHANT_ID.toString());
        assertThat(lastDownstream.get().principal()).isEqualTo(InternalPrincipal.SESSION.wireValue());
    }

    @Test
    void theSessionIsGrantedTheWholeScopeVocabularyButNeverTheWildcard() throws Exception {
        client().get().uri("/v1/payments")
                .header("Authorization", "Bearer " + signedJwt(ONBOARDED_USER_ID.toString()))
                .exchange()
                .expectStatus().isOk();

        Set<String> granted = Set.of(lastDownstream.get().scopes().split(","));
        assertThat(granted).isEqualTo(ApiScopes.ALL);
        // The wildcard short-circuits the gateway's scope check; granting it to a session
        // would stop that check running on exactly the traffic a human drives.
        assertThat(granted).doesNotContain(ApiScopes.WILDCARD);
    }

    // ---------------------------------------------------------------------------------
    // Mode (D184).
    // ---------------------------------------------------------------------------------

    @Test
    void modeDefaultsToTestWhenTheHeaderIsAbsent() throws Exception {
        client().get().uri("/v1/payments")
                .header("Authorization", "Bearer " + signedJwt(ONBOARDED_USER_ID.toString()))
                .exchange()
                .expectStatus().isOk();

        // A mis-sent header must never land a human on live data by accident.
        assertThat(lastDownstream.get().mode()).isEqualTo("test");
    }

    @Test
    void aSessionMaySelectLiveMode() throws Exception {
        client().get().uri("/v1/payments")
                .header("Authorization", "Bearer " + signedJwt(ONBOARDED_USER_ID.toString()))
                .header(SessionContextWebFilter.MODE_HEADER, "live")
                .exchange()
                .expectStatus().isOk();

        assertThat(lastDownstream.get().mode()).isEqualTo("live");
    }

    @Test
    void theSelectedModeIsConsumedAndNeverForwarded() throws Exception {
        client().get().uri("/v1/payments")
                .header("Authorization", "Bearer " + signedJwt(ONBOARDED_USER_ID.toString()))
                .header(SessionContextWebFilter.MODE_HEADER, "live")
                .exchange()
                .expectStatus().isOk();

        // The only thing any downstream service may trust about mode is the signed context.
        assertThat(lastDownstream.get().rawModeHeader()).isNull();
    }

    @Test
    void anUnrecognisedModeIsRefusedRatherThanDefaulted() throws Exception {
        client().get().uri("/v1/payments")
                .header("Authorization", "Bearer " + signedJwt(ONBOARDED_USER_ID.toString()))
                .header(SessionContextWebFilter.MODE_HEADER, "production")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("BAD_REQUEST");

        assertThat(lastDownstream.get()).isNull();
    }

    // ---------------------------------------------------------------------------------
    // Failure modes, all fail-closed.
    // ---------------------------------------------------------------------------------

    @Test
    void aUserWithNoMerchantIsRefusedWith403AndNeverReachesADownstreamService() throws Exception {
        client().get().uri("/v1/payments")
                .header("Authorization", "Bearer " + signedJwt(UUID.randomUUID().toString()))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN");

        assertThat(lastDownstream.get()).isNull();
    }

    @Test
    void aTokenSignedByAnotherKeyIsRefusedWith401() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair otherPair = generator.generateKeyPair();
        RSAKey otherKey = new RSAKey.Builder((RSAPublicKey) otherPair.getPublic())
                .privateKey((RSAPrivateKey) otherPair.getPrivate())
                .keyID("test-key")
                .build();

        client().get().uri("/v1/payments")
                .header("Authorization", "Bearer " + signedJwt(ONBOARDED_USER_ID.toString(), otherKey, ISSUER))
                .exchange()
                .expectStatus().isUnauthorized();

        assertThat(lastDownstream.get()).isNull();
    }

    @Test
    void anExpiredTokenIsRefusedWith401() throws Exception {
        Instant past = Instant.now().minus(2, ChronoUnit.HOURS);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(ONBOARDED_USER_ID.toString())
                .issuer(ISSUER)
                .issueTime(Date.from(past))
                .expirationTime(Date.from(past.plus(15, ChronoUnit.MINUTES)))
                .claim("roles", List.of("USER"))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(rsaKey.toRSAPrivateKey()));

        client().get().uri("/v1/payments")
                .header("Authorization", "Bearer " + jwt.serialize())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void aTokenWhoseSubjectIsNotAPortalUserIsRefusedWith401() throws Exception {
        // Correctly signed and correctly issued, but the subject is not a UUID — so it is
        // not a subject identity-service minted for a portal user, whatever else is true.
        client().get().uri("/v1/payments")
                .header("Authorization", "Bearer " + signedJwt("service-account-7"))
                .exchange()
                .expectStatus().isUnauthorized();

        assertThat(lastDownstream.get()).isNull();
    }

    @Test
    void anUnauthenticatedRequestToThePublicTierIsStillRefused() {
        client().get().uri("/v1/payments")
                .exchange()
                .expectStatus().isUnauthorized();

        assertThat(lastDownstream.get()).isNull();
    }

    // ---------------------------------------------------------------------------------
    // Interference with what already worked.
    // ---------------------------------------------------------------------------------

    @Test
    void aClientSuppliedInternalContextIsStrippedBeforeThisFilterCanTrustIt() throws Exception {
        client().get().uri("/v1/payments")
                .header("Authorization", "Bearer " + signedJwt(ONBOARDED_USER_ID.toString()))
                .header(InternalContextHeaders.MERCHANT_ID, UUID.randomUUID().toString())
                .header(InternalContextHeaders.PRINCIPAL, InternalPrincipal.API_KEY.wireValue())
                .header(InternalContextHeaders.KEY_ID, UUID.randomUUID().toString())
                .header(InternalContextHeaders.USER_ID, UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isOk();

        // InternalHeaderStrippingWebFilter (+1) removed all four before anything ran; the
        // context downstream sees is entirely the gateway's own. The two headers M23.0
        // added are covered by the same prefix rule, which is why they needed no change
        // there — asserted rather than assumed.
        CapturedRequest captured = lastDownstream.get();
        assertThat(captured.merchantId()).isEqualTo(MERCHANT_ID.toString());
        assertThat(captured.userId()).isEqualTo(ONBOARDED_USER_ID.toString());
        assertThat(captured.keyId()).isNull();
        assertThat(captured.principal()).isEqualTo(InternalPrincipal.SESSION.wireValue());
    }

    @Test
    void theSessionPathDoesNotTouchTheDashboardTier() throws Exception {
        // /api/v1 is the account plane and keeps its JWT passthrough exactly as before
        // (D182): the token reaches identity-service and merchant-service intact, because
        // those two authenticate the *user*, not a merchant context.
        client().get().uri("/api/v1/merchants/me")
                .header("Authorization", "Bearer " + signedJwt(ONBOARDED_USER_ID.toString()))
                .exchange()
                // The merchant stub has no such route; what matters is that the gateway
                // routed it rather than converting the credential.
                .expectStatus().isNotFound();

        assertThat(lastDownstream.get()).isNull();
    }

    @Test
    void anApiKeyStillCannotSelectItsOwnMode() {
        // The invariant M23.0 must not weaken (D184). The session path reads X-PF-Mode; this
        // proves that adding that capability did not make the header reachable from an API
        // key, whose mode stays bound to the key itself.
        //
        // Three independent things hold it: the route filter strips X-PF-Mode; the API-key
        // filter at +20 has already replaced the credential by the time this one runs at +21,
        // so there is no bearer token left for it to classify; and it classifies anyway, and
        // acts only on a JWT. Verified by mutation — deleting the route-level strip fails
        // this test on `rawModeHeader`. Widening the credential check alone does *not* fail
        // it, which is the point of having all three rather than one.
        client().get().uri("/v1/payments")
                .header("Authorization", "Bearer " + TEST_MODE_SECRET_KEY)
                .header(SessionContextWebFilter.MODE_HEADER, "live")
                .exchange()
                .expectStatus().isOk();

        CapturedRequest captured = lastDownstream.get();
        assertThat(captured.mode()).isEqualTo("test");
        assertThat(captured.rawModeHeader()).isNull();
    }

    @Test
    void anApiKeyRequestIsStillLabelledAsAnApiKeyPrincipal() {
        client().get().uri("/v1/payments")
                .header("Authorization", "Bearer " + TEST_MODE_SECRET_KEY)
                .exchange()
                .expectStatus().isOk();

        CapturedRequest captured = lastDownstream.get();
        // The API-key path signs with the M15 overload, which omits the principal header —
        // so downstream defaults it to api_key. Asserted here because "absent means api_key"
        // is the assumption that keeps M23.0 additive rather than a cutover.
        assertThat(captured.principal()).isNull();
        assertThat(captured.keyId()).isEqualTo(TEST_KEY_ID.toString());
        assertThat(captured.userId()).isNull();
    }

    @Test
    void theUnauthenticatedTestCardCatalogueStaysUnauthenticatedForANotYetOnboardedUser() throws Exception {
        // M17.8's one genuinely public /v1 path, declared security: [] in the published
        // document. A session must not be able to turn it into an authenticated endpoint,
        // which is exactly what would happen if this filter tried to resolve a merchant for
        // a user who has none: the catalogue would start answering 403 to a brand-new
        // signup, on the one endpoint that needs no credential at all.
        client().get().uri("/v1/test/cards")
                .header("Authorization", "Bearer " + signedJwt(UUID.randomUUID().toString()))
                .exchange()
                .expectStatus().isOk();

        // Reached the service, and carries no asserted context — the request was left
        // exactly as unauthenticated as it was before M23.0.
        CapturedRequest captured = lastDownstream.get();
        assertThat(captured).isNotNull();
        assertThat(captured.merchantId()).isNull();
        assertThat(captured.principal()).isNull();
        assertThat(captured.signature()).isNull();
    }

    // ---------------------------------------------------------------------------------
    // Helpers.
    // ---------------------------------------------------------------------------------

    private static Publisher<Void> capture(HttpServerRequest req, HttpServerResponse res) {
        var headers = req.requestHeaders();
        lastDownstream.set(new CapturedRequest(
                headers.get(InternalContextHeaders.MERCHANT_ID),
                headers.get(InternalContextHeaders.MODE),
                headers.get(InternalContextHeaders.PRINCIPAL),
                headers.get(InternalContextHeaders.USER_ID),
                headers.get(InternalContextHeaders.KEY_ID),
                headers.get(InternalContextHeaders.SCOPES),
                headers.get(InternalContextHeaders.CONTACT_EMAIL),
                headers.get(InternalContextHeaders.WEBHOOK_URL),
                headers.get(InternalContextHeaders.ISSUED_AT),
                headers.get(InternalContextHeaders.SIGNATURE),
                headers.get("Authorization"),
                headers.get(SessionContextWebFilter.MODE_HEADER)));
        return res.header("Content-Type", "application/json")
                .sendString(Mono.just("{\"data\":[],\"hasMore\":false,\"nextCursor\":null}"));
    }

    private record CapturedRequest(String merchantId, String mode, String principal, String userId, String keyId,
                                   String scopes, String contactEmail, String webhookUrl, String issuedAt,
                                   String signature, String authorization, String rawModeHeader) {
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

    private static String signedJwt(String subject) throws Exception {
        return signedJwt(subject, rsaKey, ISSUER);
    }

    private static String signedJwt(String subject, RSAKey key, String issuer) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(issuer)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(15, ChronoUnit.MINUTES)))
                .claim("email", "portal-user@example.com")
                .claim("roles", List.of("USER"))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key.toRSAPrivateKey()));
        return jwt.serialize();
    }
}
