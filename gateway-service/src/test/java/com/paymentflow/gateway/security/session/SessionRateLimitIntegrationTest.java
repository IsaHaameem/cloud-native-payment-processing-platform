package com.paymentflow.gateway.security.session;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
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

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M23.0's rate-limit branch, on its own because it needs a deliberately tiny bucket that
 * would starve every other session test in the class.
 *
 * <p>The property under test is easy to state and was easy to get silently wrong: by the
 * time the {@code KeyResolver} runs, {@code SessionContextWebFilter} has already replaced
 * the session's {@code Authorization} header, so the credential-shape check finds nothing,
 * and the reactive security context holds a {@code MerchantContextAuthenticationToken}
 * rather than a {@code JwtAuthenticationToken}, so the JWT branch does not match either.
 * Portal traffic would therefore fall into the shared {@code ip:} bucket — one office, one
 * address, every user competing for an allowance sized for anonymous browsers, which is the
 * failure D146 fixed for API keys and would have quietly reintroduced for the portal.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SessionRateLimitIntegrationTest {

    private static final String ISSUER = "https://identity.paymentflow.local";

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:8-alpine"))
            .withExposedPorts(6379);

    private static RSAKey rsaKey;
    private static DisposableServer identityStub;
    private static DisposableServer merchantStub;
    private static DisposableServer paymentStub;

    private static final UUID MERCHANT_ID = UUID.randomUUID();

    @LocalServerPort
    private int gatewayPort;

    @Value("${paymentflow.gateway.rate-limit.burst-capacity}")
    private int burstCapacity;

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

        // Every user owns a merchant here — this class is about buckets, not onboarding.
        merchantStub = HttpServer.create()
                .port(0)
                .route(routes -> routes.get("/internal/v1/merchants/by-owner/{ownerUserId}", (req, res) -> res
                        .header("Content-Type", "application/json")
                        .sendString(Mono.just("""
                                {"merchantId":"%s","contactEmail":"portal@acme.test","webhookUrl":null}
                                """.formatted(MERCHANT_ID)))))
                .bindNow();

        paymentStub = HttpServer.create()
                .port(0)
                .route(routes -> routes
                        .get("/v1/payments", (req, res) -> res.header("Content-Type", "application/json")
                                .sendString(Mono.just("{\"data\":[],\"hasMore\":false,\"nextCursor\":null}")))
                        .get("/v1/payments/**", (req, res) -> res.header("Content-Type", "application/json")
                                .sendString(Mono.just("{\"data\":[],\"hasMore\":false,\"nextCursor\":null}"))))
                .bindNow();
    }

    @AfterAll
    static void stopStubs() {
        for (DisposableServer server : List.of(identityStub, merchantStub, paymentStub)) {
            if (server != null) {
                server.disposeNow();
            }
        }
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("paymentflow.services.identity.base-uri", () -> "http://localhost:" + identityStub.port());
        registry.add("paymentflow.services.merchant.base-uri", () -> "http://localhost:" + merchantStub.port());
        registry.add("paymentflow.services.payment.base-uri", () -> "http://localhost:" + paymentStub.port());
        // Small and deterministic, exactly as GatewayIntegrationTest does, so the test does
        // not depend on firing dozens of requests.
        registry.add("paymentflow.gateway.rate-limit.replenish-rate", () -> 1);
        registry.add("paymentflow.gateway.rate-limit.burst-capacity", () -> 5);
        registry.add("paymentflow.gateway.rate-limit.requested-tokens", () -> 1);
    }

    @Test
    void portalTrafficIsRateLimited() throws Exception {
        String token = signedJwt(UUID.randomUUID().toString());

        assertThat(statusesFor(token, burstCapacity + 5)).contains(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void oneUserExhaustingTheirBucketDoesNotRefuseAnother() throws Exception {
        String busy = signedJwt(UUID.randomUUID().toString());
        String quiet = signedJwt(UUID.randomUUID().toString());

        statusesFor(busy, burstCapacity + 5);

        // The whole point of the branch. If portal traffic fell into the shared "ip:" bucket
        // — which is what happens without it, since both users reach the gateway from the
        // same address — this second user would already be refused.
        assertThat(statusesFor(quiet, 1)).containsExactly(HttpStatus.OK);
    }

    private List<HttpStatusCode> statusesFor(String token, int requests) {
        List<HttpStatusCode> statuses = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            statuses.add(client().get().uri("/v1/payments")
                    .header("Authorization", "Bearer " + token)
                    .exchange()
                    .returnResult(String.class)
                    .getStatus());
        }
        return statuses;
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
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(ISSUER)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(15, ChronoUnit.MINUTES)))
                .claim("roles", List.of("USER"))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(rsaKey.toRSAPrivateKey()));
        return jwt.serialize();
    }
}
