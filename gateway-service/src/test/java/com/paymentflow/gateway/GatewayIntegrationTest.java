package com.paymentflow.gateway;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full black-box verification of the gateway's M3 responsibilities: routing to
 * identity-service, JWT validation via JWKS, Redis-backed rate limiting, CORS, security
 * headers, and correlation-id propagation — against a real bound gateway (random port),
 * a real Redis (Testcontainers), and a minimal Reactor Netty stub standing in for
 * identity-service (its own JWKS + auth endpoints, so the gateway performs real
 * signature/issuer validation against real key material).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class GatewayIntegrationTest {

    private static final String ISSUER = "https://identity.paymentflow.local";

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:8-alpine"))
            .withExposedPorts(6379);

    private static RSAKey rsaKey;
    private static DisposableServer identityStub;
    private static final AtomicReference<String> lastAuthorizationHeaderSeen = new AtomicReference<>();
    private static final AtomicReference<String> lastCorrelationIdSeen = new AtomicReference<>();

    @LocalServerPort
    private int gatewayPort;

    @Value("${paymentflow.gateway.rate-limit.burst-capacity}")
    private int burstCapacity;

    private WebTestClient client;

    @BeforeAll
    static void startFakeIdentityService() throws Exception {
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
                .route(routes -> routes
                        .get("/oauth2/jwks", (req, res) -> res.header("Content-Type", "application/json")
                                .sendString(Mono.just(jwksJson)))
                        .get("/api/v1/auth/ping", (req, res) -> res.header("Content-Type", "application/json")
                                .sendString(Mono.just("{\"status\":\"ok\"}")))
                        .get("/api/v1/users/me", (req, res) -> {
                            String authHeader = req.requestHeaders().get("Authorization");
                            lastAuthorizationHeaderSeen.set(authHeader);
                            lastCorrelationIdSeen.set(req.requestHeaders().get("X-Correlation-Id"));
                            return res.header("Content-Type", "application/json")
                                    .sendString(Mono.just("{\"authorizationHeader\":\"" + authHeader + "\"}"));
                        }))
                .bindNow();
    }

    @AfterAll
    static void stopFakeIdentityService() {
        if (identityStub != null) {
            identityStub.disposeNow();
        }
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("paymentflow.services.identity.base-uri", () -> "http://localhost:" + identityStub.port());
        // Small, deterministic burst capacity so the rate-limit test doesn't depend on
        // firing dozens of requests; unrelated tests use unique keys (per-user or the
        // shared "ip:" bucket, which sees only two calls total across this class) so
        // they never brush against this ceiling.
        registry.add("paymentflow.gateway.rate-limit.replenish-rate", () -> 1);
        registry.add("paymentflow.gateway.rate-limit.burst-capacity", () -> 5);
        registry.add("paymentflow.gateway.rate-limit.requested-tokens", () -> 1);
    }

    @BeforeEach
    void setUpClient() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + gatewayPort)
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Test
    void routesPublicAuthEndpointToIdentityWithoutAuthentication() {
        client.get().uri("/api/v1/auth/ping")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("ok");
    }

    @Test
    void protectedEndpointWithoutTokenReturns401ApiError() {
        client.get().uri("/api/v1/users/me")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHORIZED");
    }

    @Test
    void protectedEndpointWithMalformedTokenReturns401() {
        client.get().uri("/api/v1/users/me")
                .header("Authorization", "Bearer not-a-real-jwt")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void protectedEndpointWithValidTokenIsRoutedWithAuthorizationAndCorrelationIdForwarded() throws Exception {
        String token = signedJwt(UUID.randomUUID().toString());

        client.get().uri("/api/v1/users/me")
                .header("Authorization", "Bearer " + token)
                .header("X-Correlation-Id", "test-correlation-42")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Correlation-Id", "test-correlation-42")
                .expectBody()
                .jsonPath("$.authorizationHeader").isEqualTo("Bearer " + token);

        assertThat(lastAuthorizationHeaderSeen.get()).isEqualTo("Bearer " + token);
        assertThat(lastCorrelationIdSeen.get()).isEqualTo("test-correlation-42");
    }

    @Test
    void unmappedPathReturns404ApiError() throws Exception {
        // Authenticated so the request clears the security layer's anyExchange().authenticated()
        // gate and actually reaches route matching — an unauthenticated call to an unmapped
        // path fails closed with 401 before routing is ever attempted (asserted separately).
        String token = signedJwt(UUID.randomUUID().toString());

        client.get().uri("/does-not-exist")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("NOT_FOUND");
    }

    @Test
    void unmappedPathWithoutAuthenticationFailsClosedWith401() {
        client.get().uri("/does-not-exist")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHORIZED");
    }

    @Test
    void responsesCarrySecurityHeaders() {
        client.get().uri("/api/v1/auth/ping")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Frame-Options", "DENY")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().exists("Content-Security-Policy")
                .expectHeader().exists("Permissions-Policy");
    }

    @Test
    void corsPreflightFromAllowedOriginIsAccepted() {
        client.options().uri("/api/v1/auth/ping")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "GET")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:3000");
    }

    @Test
    void rateLimiterReturns429OnceBurstCapacityIsExceeded() throws Exception {
        String token = signedJwt("rate-limit-test-" + UUID.randomUUID());
        List<HttpStatusCode> statuses = new ArrayList<>();

        for (int i = 0; i < burstCapacity + 5; i++) {
            HttpStatusCode status = client.get().uri("/api/v1/users/me")
                    .header("Authorization", "Bearer " + token)
                    .exchange()
                    .returnResult(String.class)
                    .getStatus();
            statuses.add(status);
        }

        assertThat(statuses).contains(HttpStatus.TOO_MANY_REQUESTS);
    }

    private static String signedJwt(String subject) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(ISSUER)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(15, ChronoUnit.MINUTES)))
                .claim("email", subject + "@example.com")
                .claim("roles", List.of("USER"))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(rsaKey.toRSAPrivateKey()));
        return jwt.serialize();
    }
}
