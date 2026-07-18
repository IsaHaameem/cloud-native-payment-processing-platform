package com.paymentflow.merchant;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.paymentflow.merchant.repository.ApiKeyRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full onboarding/self-service flow against a real Postgres and Redis. JWTs are signed
 * locally with a test RSA key served from a plain JDK {@link HttpServer} standing in for
 * identity-service's JWKS endpoint (no extra test dependency; merchant-service performs
 * real signature validation against real key material, not a mocked decoder).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MerchantIntegrationTest {

    private static final String ISSUER = "https://identity.paymentflow.local";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:8-alpine"))
            .withExposedPorts(6379);

    private static RSAKey rsaKey;
    private static HttpServer jwksStub;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @BeforeAll
    static void startJwksStub() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID("test-key")
                .build();

        String jwksJson = new JWKSet(rsaKey.toPublicJWK()).toString();

        jwksStub = HttpServer.create(new InetSocketAddress(0), 0);
        jwksStub.createContext("/oauth2/jwks", exchange -> {
            byte[] bytes = jwksJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) {
                body.write(bytes);
            }
        });
        jwksStub.start();
    }

    @AfterAll
    static void stopJwksStub() {
        if (jwksStub != null) {
            jwksStub.stop(0);
        }
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("paymentflow.services.identity.jwks-uri",
                () -> "http://localhost:" + jwksStub.getAddress().getPort() + "/oauth2/jwks");
    }

    @Test
    void onboardThenGetMineReturnsTheSameProfile() throws Exception {
        String subject = UUID.randomUUID().toString();
        String token = signedJwt(subject, List.of("USER"));

        mockMvc.perform(post("/api/v1/merchants")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new com.paymentflow.merchant.dto.OnboardMerchantRequest("Acme Corp", "billing@acme.test"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.merchant.businessName").value("Acme Corp"))
                .andExpect(jsonPath("$.apiKey.apiKey").value(org.hamcrest.Matchers.startsWith("pf_")));

        mockMvc.perform(get("/api/v1/merchants/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessName").value("Acme Corp"))
                .andExpect(jsonPath("$.contactEmail").value("billing@acme.test"));
    }

    @Test
    void onboardingTwiceForTheSameOwnerReturnsConflict() throws Exception {
        String subject = UUID.randomUUID().toString();
        String token = signedJwt(subject, List.of("USER"));
        onboard(token, "First Co", "first@example.test");

        mockMvc.perform(post("/api/v1/merchants")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new com.paymentflow.merchant.dto.OnboardMerchantRequest("Second Co", "second@example.test"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void currentMerchantWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/merchants/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void repeatedReadsOfAnUnchangedProfileHitTheCacheAndReturnTheSameValue() throws Exception {
        // Regression test: GenericJacksonJsonRedisSerializer needs embedded type info
        // to deserialize a cache HIT back into MerchantResponse (not a raw
        // LinkedHashMap) — a plain ObjectMapper without default typing enabled
        // throws ClassCastException on exactly this second read. The cache-busting
        // test above never exercises a real cache hit (its second read always
        // follows an eviction), so it alone didn't catch this.
        String subject = UUID.randomUUID().toString();
        String token = signedJwt(subject, List.of("USER"));
        onboard(token, "Cached Co", "cached@example.test");

        mockMvc.perform(get("/api/v1/merchants/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessName").value("Cached Co"));

        // Second read is a genuine cache hit — no eviction happened in between.
        mockMvc.perform(get("/api/v1/merchants/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessName").value("Cached Co"))
                .andExpect(jsonPath("$.contactEmail").value("cached@example.test"));
    }

    @Test
    void updatingProfileBustsTheCacheSoSubsequentReadsSeeTheNewValue() throws Exception {
        String subject = UUID.randomUUID().toString();
        String token = signedJwt(subject, List.of("USER"));
        onboard(token, "Original Name", "original@example.test");

        // First read populates the cache.
        mockMvc.perform(get("/api/v1/merchants/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.businessName").value("Original Name"));

        mockMvc.perform(patch("/api/v1/merchants/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new com.paymentflow.merchant.dto.UpdateMerchantRequest("Renamed Co", "renamed@example.test"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessName").value("Renamed Co"));

        // If the cache weren't busted, this would still return "Original Name".
        mockMvc.perform(get("/api/v1/merchants/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.businessName").value("Renamed Co"));
    }

    @Test
    void settingWebhookBustsTheCacheAndClearingItSetsNull() throws Exception {
        String subject = UUID.randomUUID().toString();
        String token = signedJwt(subject, List.of("USER"));
        onboard(token, "Webhook Co", "hooks@example.test");

        // First read populates the cache with webhookUrl == null.
        mockMvc.perform(get("/api/v1/merchants/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.webhookUrl").doesNotExist());

        mockMvc.perform(patch("/api/v1/merchants/me/webhook")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new com.paymentflow.merchant.dto.UpdateWebhookRequest("https://webhook-co.test/hooks"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.webhookUrl").value("https://webhook-co.test/hooks"));

        // If the cache weren't busted, this would still show no webhook.
        mockMvc.perform(get("/api/v1/merchants/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.webhookUrl").value("https://webhook-co.test/hooks"));

        mockMvc.perform(patch("/api/v1/merchants/me/webhook")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new com.paymentflow.merchant.dto.UpdateWebhookRequest(null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.webhookUrl").doesNotExist());
    }

    @Test
    void settingWebhookWithNonHttpsUrlReturns400ValidationError() throws Exception {
        String token = signedJwt(UUID.randomUUID().toString(), List.of("USER"));
        onboard(token, "Insecure Co", "insecure@example.test");

        mockMvc.perform(patch("/api/v1/merchants/me/webhook")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new com.paymentflow.merchant.dto.UpdateWebhookRequest("http://insecure.test/hooks"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rotatingApiKeyRevokesThePreviousOneAndIssuesADifferentValue() throws Exception {
        String subject = UUID.randomUUID().toString();
        String token = signedJwt(subject, List.of("USER"));
        String firstRawKey = onboard(token, "Acme", "billing@acme.test");

        String body = mockMvc.perform(post("/api/v1/merchants/me/api-key/rotate")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String secondRawKey = objectMapper.readTree(body).get("apiKey").asString();

        assertThat(secondRawKey).isNotEqualTo(firstRawKey);

        var keysForThisMerchant = apiKeyRepository.findAll().stream()
                .filter(key -> key.getKeyHash().equals(sha256Hex(firstRawKey)) || key.getKeyHash().equals(sha256Hex(secondRawKey)))
                .toList();
        assertThat(keysForThisMerchant).hasSize(2);
        assertThat(keysForThisMerchant).filteredOn(key -> key.getKeyHash().equals(sha256Hex(firstRawKey)))
                .allMatch(key -> !key.isActive());
        assertThat(keysForThisMerchant).filteredOn(key -> key.getKeyHash().equals(sha256Hex(secondRawKey)))
                .allMatch(com.paymentflow.merchant.domain.ApiKey::isActive);
    }

    private static String sha256Hex(String raw) {
        return com.paymentflow.common.security.OpaqueTokenGenerator.sha256Hex(raw);
    }

    @Test
    void nonAdminCannotListMerchantsButAdminCan() throws Exception {
        String userToken = signedJwt(UUID.randomUUID().toString(), List.of("USER"));
        onboard(userToken, "Listed Co", "listed@example.test");

        mockMvc.perform(get("/api/v1/merchants").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        String adminToken = signedJwt(UUID.randomUUID().toString(), List.of("ADMIN"));
        mockMvc.perform(get("/api/v1/merchants").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void onboardWithBlankBusinessNameReturns400ValidationError() throws Exception {
        String token = signedJwt(UUID.randomUUID().toString(), List.of("USER"));

        mockMvc.perform(post("/api/v1/merchants")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new com.paymentflow.merchant.dto.OnboardMerchantRequest("", "billing@acme.test"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private String onboard(String token, String businessName, String contactEmail) throws Exception {
        String body = mockMvc.perform(post("/api/v1/merchants")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new com.paymentflow.merchant.dto.OnboardMerchantRequest(businessName, contactEmail))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("apiKey").get("apiKey").asString();
    }

    private static String signedJwt(String subject, List<String> roles) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(ISSUER)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(15, ChronoUnit.MINUTES)))
                .claim("email", subject + "@example.com")
                .claim("roles", roles)
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(rsaKey.toRSAPrivateKey()));
        return jwt.serialize();
    }
}
